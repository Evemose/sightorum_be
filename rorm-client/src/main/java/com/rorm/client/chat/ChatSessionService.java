package com.rorm.client.chat;

import com.rorm.ai.chat.*;
import com.rorm.ai.chat.dto.SubconclusionDTO;
import com.rorm.ai.chat.dto.SubconclusionMapper;
import com.rorm.ai.chat.node.ChatForkedNode;
import com.rorm.ai.chat.node.ChatNodeRepository;
import com.rorm.ai.chat.node.MessageNode;
import com.rorm.client.chat.dto.*;
import com.rorm.client.metamodel.MetamodelService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Service for managing chat sessions and message streaming.
 * Provides session lifecycle management and delegates AI interactions to {@link AiChatService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatProgressRepository progressRepository;
    private final MetamodelService metamodelService;
    private final AiChatService aiChatService;
    private final ClientChatNodeMapper nodeMapper;
    private final ChatEventPublisher eventPublisher;
    private final ChatNodeRepository chatNodeRepository;
    private final SubconclusionMapper subconclusionMapper;
    private final ConcurrentMap<String, Consumer<String>> chatBuffers = new ConcurrentHashMap<>();

    @Transactional
    public ChatSessionResponse createSession(CreateSessionRequest request) {
        var modelSpace = metamodelService.getModelSpace(request.schemaName());
        var progress = progressRepository.save(new ChatProgress(modelSpace));
        log.info("Created chat session {} for schema '{}'", progress.getId(), request.schemaName());
        return toResponse(progress, request.schemaName());
    }

    private ChatSessionResponse toResponse(ChatProgress progress, String schemaName) {
        return new ChatSessionResponse(
            progress.getId(),
            progress.getStatus().name(),
            schemaName,
            progress.getParent().map(ChatProgress::getId).orElse(null),
            progress.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public ChatSessionResponse getSession(UUID sessionId) {
        var progress = findProgressOrThrow(sessionId);
        return toResponse(progress, extractSchemaName(progress));
    }

    private ChatProgress findProgressOrThrow(UUID sessionId) {
        return progressRepository.findById(sessionId)
            .orElseThrow(() -> new EntityNotFoundException("Session not found: " + sessionId));
    }

    private String extractSchemaName(ChatProgress progress) {
        var modelSpace = progress.getModelSpace();
        if (modelSpace != null && !modelSpace.roots().isEmpty()) {
            return modelSpace.roots().iterator().next().primaryTableName();
        }
        return "unknown";
    }

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> listSessions() {
        return progressRepository.findAll().stream()
            .map(p -> toResponse(p, extractSchemaName(p)))
            .toList();
    }

    @Transactional
    public void deleteSession(UUID sessionId) {
        if (!progressRepository.existsById(sessionId)) {
            throw new EntityNotFoundException("Session not found: " + sessionId);
        }
        progressRepository.deleteById(sessionId);
        log.info("Deleted chat session {}", sessionId);
    }

    @Transactional(readOnly = true)
    public ChatBranchDTO getChatTree(UUID sessionId) {
        var progress = findProgressOrThrow(sessionId);
        var root = findRootProgress(progress);
        return toBranchWithChildren(root);
    }

    private ChatProgress findRootProgress(ChatProgress progress) {
        var current = progress;
        while (current.getParent().isPresent()) {
            current = current.getParent().get();
        }
        return current;
    }

    private ChatBranchDTO toBranchWithChildren(ChatProgress progress) {
        var nodes = progress.getNodes().stream()
            .map(nodeMapper::toDTO)
            .toList();

        var children = progressRepository.findByParent_Id(progress.getId()).stream()
            .map(this::toBranchWithChildren)
            .toList();

        return new ChatBranchDTO(
            progress.getId(),
            progress.getStatus().name(),
            progress.getNodes().stream()
                .filter(ChatForkedNode.class::isInstance)
                .map(ChatForkedNode.class::cast)
                .map(fp -> new ChatBranchDTO.ForkPoint(fp.getForkPoint().getId(), fp.getReason()))
                .findAny()
                .orElse(null),
            nodes,
            children
        );
    }

    /**
     * Sends a correction message at a specific point in the conversation.
     * All nodes at and after the specified node will be deleted, then the correction
     * message is inserted and processed by the AI.
     * Events are published for the truncation, new message, tokens, and completion.
     *
     * @param sessionId the session to correct
     * @param request   contains the node ID to insert before and the correction message
     * @throws EntityNotFoundException  if session or node is not found
     * @throws IllegalArgumentException if the specified node is not found in this session
     */
    @Transactional
    public void sendCorrection(UUID sessionId, CorrectionRequest request) {
        var progress = findProgressOrThrow(sessionId);

        var truncated = progress.truncateNodesFrom(request.beforeNodeId());
        if (!truncated) {
            throw new IllegalArgumentException("Node not found in session: " + request.beforeNodeId());
        }
        log.debug("Truncated nodes from {} in session {}", request.beforeNodeId(), sessionId);

        progressRepository.deleteAll(findAllDangling(progress));

        sendMessage(sessionId, new SendMessageRequest(request.message()));
    }

    private List<ChatProgress> findAllDangling(ChatProgress progress) {
        var allFlat = progressRepository.findByParentIdRecursive(progress.getId());
        return allFlat.stream().filter(this::isDangling).toList();
    }

    /**
     * Sends a message and returns a streaming response.
     * The user message is persisted and published before streaming begins.
     * Temporary node updates are published as content arrives (in-memory, not persisted).
     * After streaming completes, a delete event is sent for the temporary node,
     * then the final subconclusion node is created and published.
     */
    @Transactional
    public void sendMessage(UUID sessionId, SendMessageRequest request) {
        var progress = findProgressOrThrow(sessionId);

        var userNode = MessageNode.user(request.message());
        progress.addNode(userNode);
        progressRepository.saveAndFlush(progress);
        log.debug("Added user message to session {}", sessionId);

        // Get the persisted node with ID assigned (last node in the list after flush)
        var persistedUserNode = progress.getNodes().getLast();
        eventPublisher.publishNode(sessionId, persistedUserNode);

        var conversationId = UUID.randomUUID().toString();
        var chatRequest = ChatRequest.proceedingOnSchema(extractSchemaName(progress), progress)
            .withChatId(conversationId)
            .ask(request.message());

        // In-memory accumulator for streaming content
        @SuppressWarnings("java:S1149")
        var contentBuilder = new StringBuffer();
        var temporaryNode = new ChatNodeDTO.Temporary(UUID.randomUUID(), Instant.now(), "");

        aiChatService.stream(chatRequest)
            .doOnSubscribe(_ -> {
                eventPublisher.publishNode(sessionId, temporaryNode);
                chatBuffers.put(conversationId, token ->
                    eventPublisher.publishNodeUpdate(sessionId, temporaryNode.withInProgressContent(
                        contentBuilder.append(token).toString()
                    )));
            })
            .doOnNext(token -> eventPublisher.publishNodeUpdate(sessionId, temporaryNode.withInProgressContent(
                contentBuilder.append(token).toString()
            )))
            .doOnComplete(() -> chatBuffers.remove(conversationId))
            .then(Mono.defer(() -> {
                if (!contentBuilder.isEmpty()) {
                    return Mono.fromCallable(() -> {
                        var extractionPrompt = buildExtractionPrompt(contentBuilder.toString());

                        var extractionRequest = ChatRequest.proceedingOnSchema(extractSchemaName(progress), progress)
                            .withChatId(conversationId)
                            .ask(extractionPrompt, SubconclusionDTO.class);
                        var subconclusion = aiChatService.call(extractionRequest);

                        var subconclusionNode = chatNodeRepository.save(
                            subconclusionMapper.toEntity(subconclusion, conversationId)
                        );

                        progress.addNode(subconclusionNode);
                        progressRepository.saveAndFlush(progress);

                        eventPublisher.publishNodeDeleted(sessionId, temporaryNode.id());
                        eventPublisher.publishNode(sessionId, subconclusionNode);

                        return subconclusionNode;
                    }).subscribeOn(Schedulers.boundedElastic()).then();
                } else {
                    log.debug("No assistant message generated for session {}", sessionId);
                    eventPublisher.publishNodeDeleted(sessionId, temporaryNode.id());
                    return Mono.empty();
                }
            }))
            .doOnSuccess(_ -> {
                log.debug("Completed AI response for session {}", sessionId);
                eventPublisher.publishStreamComplete(sessionId);
            })
            .doOnError(e -> {
                log.error("Error in AI response for session {}: {}", sessionId, e.getMessage());
                eventPublisher.publishStreamError(sessionId, e.getMessage());
            })
            .subscribe();
    }

    private boolean isDangling(ChatProgress current) {
        while (current.getParent().isPresent()) {
            var firstNode = current.getNodes().getFirst();
            if (!(firstNode instanceof ChatForkedNode forkNode)) {
                return false;
            }
            if (!current.getParent().get().getNodes().contains(forkNode.getForkPoint())) {
                return false;
            }
        }
        return true;
    }

    private static @NonNull String buildExtractionPrompt(String rawResponse) {
        return """
            Based on your previous response, provide a structured summary.
            
            Your previous response was:
            ---
            %s
            ---
            """.formatted(rawResponse);
    }

    @EventListener(MessageAddedEvent.class)
    public void handleMessageAdded(MessageAddedEvent event) {
        var sessionId = event.getConversationId();
        var tokenConsumer = chatBuffers.get(sessionId);
        if (tokenConsumer != null) {
            tokenConsumer.accept(event.getMessage().getText());
        }
    }
}
