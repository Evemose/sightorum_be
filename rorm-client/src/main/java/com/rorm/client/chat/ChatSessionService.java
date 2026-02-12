package com.rorm.client.chat;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatProgress;
import com.rorm.ai.chat.ChatProgressRepository;
import com.rorm.ai.chat.node.ChatForkedNode;
import com.rorm.ai.chat.node.MessageNode;
import com.rorm.ai.swarm.Swarm;
import com.rorm.ai.swarm.SwarmConfig;
import com.rorm.client.chat.dto.*;
import com.rorm.client.metamodel.MetamodelService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

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
    private final SwarmEventPublisher swarmEventPublisher;
    private final SwarmConfig swarmConfig;
    private final VectorStore vectorStore;

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
        throw new UnsupportedOperationException("Correction feature is not implemented yet");
    }

    /**
     * Initiates a swarm research session for the given query.
     * Publishes swarm events via SSE as the research progresses.
     */
    @Transactional
    public void researchWithSwarm(UUID sessionId, SendMessageRequest request) {
        var progress = findProgressOrThrow(sessionId);

        var userNode = MessageNode.user(request.message());
        progress.addNode(userNode);
        progressRepository.saveAndFlush(progress);
        log.debug("Added user research query to session {}", sessionId);

        var persistedUserNode = progress.getNodes().getLast();
        eventPublisher.publishNode(sessionId, persistedUserNode);

        var swarm = new Swarm(swarmConfig, aiChatService, progress, vectorStore);

        swarm.research(request.message())
            .doOnNext(event -> swarmEventPublisher.publish(sessionId, event))
            .doOnComplete(() -> {
                log.debug("Swarm research completed for session {}", sessionId);
                eventPublisher.publishStreamComplete(sessionId);
            })
            .doOnError(e -> {
                log.error("Error in swarm research for session {}: {}", sessionId, e.getMessage());
                eventPublisher.publishStreamError(sessionId, e.getMessage());
            })
            .subscribe();
    }
}
