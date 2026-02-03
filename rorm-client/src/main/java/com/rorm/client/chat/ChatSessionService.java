package com.rorm.client.chat;

import com.rorm.ai.RormAiService;
import com.rorm.ai.chat.ChatProgress;
import com.rorm.ai.chat.ChatProgressRepository;
import com.rorm.ai.chat.node.FailureNode;
import com.rorm.ai.chat.node.MessageNode;
import com.rorm.client.chat.dto.*;
import com.rorm.client.metamodel.MetamodelService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatProgressRepository chatProgressRepository;
    private final MetamodelService metamodelService;
    private final RormAiService rormAiService;
    private final ChatEventPublisher eventPublisher;
    private final ChatNodeMapper nodeMapper;

    // Map of session ID to interrupt flag
    private final ConcurrentHashMap<UUID, AtomicBoolean> interruptFlags = new ConcurrentHashMap<>();

    @Transactional
    public ChatSessionResponse createSession(CreateSessionRequest request) {
        var modelSpace = metamodelService.getModelSpace(request.schemaName());
        var progress = new ChatProgress(modelSpace);
        var saved = chatProgressRepository.save(progress);

        return toResponse(saved, request.schemaName());
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
    public List<ChatSessionResponse> listSessions() {
        return chatProgressRepository.findAll().stream()
            .map(p -> toResponse(p, extractSchemaName(p)))
            .toList();
    }

    private String extractSchemaName(ChatProgress progress) {
        var modelSpace = progress.getModelSpace();
        if (modelSpace != null && !modelSpace.roots().isEmpty()) {
            return modelSpace.roots().iterator().next().primaryTableName();
        }
        return "unknown";
    }

    @Transactional(readOnly = true)
    public ChatSessionResponse getSession(UUID sessionId) {
        var progress = findProgress(sessionId);
        return toResponse(progress, extractSchemaName(progress));
    }

    private ChatProgress findProgress(UUID sessionId) {
        return chatProgressRepository.findById(sessionId)
            .orElseThrow(() -> new EntityNotFoundException("Session not found: " + sessionId));
    }

    @Transactional
    public void deleteSession(UUID sessionId) {
        if (!chatProgressRepository.existsById(sessionId)) {
            throw new EntityNotFoundException("Session not found: " + sessionId);
        }
        chatProgressRepository.deleteById(sessionId);
    }

    @Transactional(readOnly = true)
    public ChatTreeResponse getChatTree(UUID sessionId) {
        var progress = findProgress(sessionId);
        var rootProgress = findRootProgress(progress);

        var mainBranch = buildBranch(rootProgress);
        var forks = buildForks(rootProgress);

        return new ChatTreeResponse(rootProgress.getId(), mainBranch, forks);
    }

    private ChatProgress findRootProgress(ChatProgress progress) {
        var current = progress;
        while (current.getParent().isPresent()) {
            current = current.getParent().get();
        }
        return current;
    }

    private ChatBranchDTO buildBranch(ChatProgress progress) {
        var nodes = progress.getMemoryNodes().stream()
            .map(nodeMapper::toDTO)
            .toList();

        return new ChatBranchDTO(
            progress.getId(),
            progress.getParent().map(ChatProgress::getId).orElse(null),
            progress.getStatus().name(),
            null,
            nodes
        );
    }

    private List<ChatBranchDTO> buildForks(ChatProgress rootProgress) {
        // In current implementation, forks are child ChatProgress entities
        // This would need a query to find children - for now return empty
        // TODO: Add query to find children by parent_id
        return new ArrayList<>();
    }

    @Transactional
    @Async("sseTaskExecutor")
    public void sendMessage(UUID sessionId, SendMessageRequest request) {
        var progress = findProgress(sessionId);

        // Add user message node
        var userNode = MessageNode.user(request.message());
        progress.addNode(userNode);
        chatProgressRepository.save(progress);

        // Publish the user message
        eventPublisher.publishNode(sessionId, userNode);

        // Process with AI
        processAiResponse(sessionId, progress, request.message());
    }

    private void processAiResponse(UUID sessionId, ChatProgress progress, String prompt) {
        try {
            // Clear interrupt flag
            var flag = interruptFlags.get(sessionId);
            if (flag != null) {
                flag.set(false);
            }

            // Check for streaming support
            var response = rormAiService.getChatClient()
                .prompt()
                .system(buildSystemPrompt(progress))
                .toolContext(new com.rorm.ai.RormToolContext(progress).toMap())
                .user(prompt)
                .advisors(
                    org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
                        .builder(rormAiService.getChatMemory())
                        .conversationId(progress.getConversationId().toString())
                        .build()
                )
                .stream()
                .content();

            var fullResponse = new StringBuilder();
            response.subscribe(
                token -> {
                    // Check for interrupt
                    var interruptFlag = interruptFlags.get(sessionId);
                    if (interruptFlag != null && interruptFlag.get()) {
                        throw new RuntimeException("Interrupted by user");
                    }

                    fullResponse.append(token);
                    eventPublisher.publishToken(sessionId, token);
                },
                error -> {
                    log.error("Error during AI response for session {}: {}", sessionId, error.getMessage());
                    eventPublisher.publishStreamError(sessionId, error.getMessage());
                },
                () -> {
                    // Add assistant message node
                    var assistantNode = MessageNode.assistant(fullResponse.toString());
                    progress.addNode(assistantNode);
                    chatProgressRepository.save(progress);
                    eventPublisher.publishNode(sessionId, assistantNode);
                    eventPublisher.publishStreamComplete(sessionId);
                }
            );

        } catch (Exception e) {
            log.error("Error processing AI response for session {}: {}", sessionId, e.getMessage(), e);
            var failureNode = new FailureNode("AI processing error: " + e.getMessage(), null);
            progress.addNode(failureNode);
            progress.fail();
            chatProgressRepository.save(progress);
            eventPublisher.publishNode(sessionId, failureNode);
            eventPublisher.publishStatusUpdate(sessionId, "FAILED");
        }
    }

    private String buildSystemPrompt(ChatProgress progress) {
        var contextBuilder = new com.rorm.ai.MetamodelContextBuilder();
        var schemaContext = contextBuilder.buildContext(progress.getModelSpace());
        return rormAiService.getProperties().systemPrompt() + "\n\n" + schemaContext;
    }

    @Transactional
    public void sendCorrection(UUID sessionId, CorrectionRequest request) {
        var progress = findProgress(sessionId);

        if (request.type() == CorrectionRequest.CorrectionType.INTERRUPT) {
            // Set interrupt flag
            interruptFlags.computeIfAbsent(sessionId, k -> new AtomicBoolean())
                .set(true);

            // Add failure node documenting the interruption
            var failureNode = new FailureNode("User interrupted: " + request.message(), null);
            progress.addNode(failureNode);
            chatProgressRepository.save(progress);
            eventPublisher.publishNode(sessionId, failureNode);
        }

        // Add user correction message
        var userNode = MessageNode.user(request.message());
        progress.addNode(userNode);
        chatProgressRepository.save(progress);
        eventPublisher.publishNode(sessionId, userNode);

        // Process correction with AI
        processAiResponse(sessionId, progress, request.message());
    }
}
