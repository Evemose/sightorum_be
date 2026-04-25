package com.rorm.client.chat;

import com.rorm.client.chat.dto.ChatMessageDTO;
import com.rorm.client.chat.dto.SessionDetailDTO;
import com.rorm.client.chat.dto.SessionSummaryDTO;
import com.rorm.client.chat.session.Session;
import com.rorm.client.chat.session.SessionAnalysis;
import com.rorm.client.chat.session.SessionService;
import com.rorm.client.import_.ImportJobService;
import com.rorm.client.import_.dto.ImportJobResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ImportJobService importJobService;

    @GetMapping
    public List<SessionSummaryDTO> list() {
        return sessionService.list().stream()
            .map(s -> new SessionSummaryDTO(
                s.getId(),
                chatMemoryRepository.findByConversationId(s.getPrimaryChatId()).size()))
            .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionDetailDTO> get(@PathVariable String id) {
        Session session;
        try {
            session = sessionService.get(id);
        } catch (EntityNotFoundException _) {
            return ResponseEntity.notFound().build();
        }

        var messages = chatMemoryRepository.findByConversationId(session.getPrimaryChatId())
            .stream().map(SessionController::toMessageDto).toList();

        var analyses = sessionService.analysesFor(id).stream()
            .map(SessionController::toAnalysisRef).toList();

        var imports = sessionService.importJobIdsFor(id).stream()
            .map(this::loadImport)
            .filter(java.util.Objects::nonNull)
            .toList();

        return ResponseEntity.ok(new SessionDetailDTO(
            session.getId(),
            session.getTitle(),
            session.getSchemaName(),
            session.getCreatedAt(),
            session.getUpdatedAt(),
            messages,
            analyses,
            imports
        ));
    }

    private static ChatMessageDTO toMessageDto(Message message) {
        var role = message.getMessageType().name().toLowerCase();
        var metadata = message.getMetadata().isEmpty() ? null : message.getMetadata();

        List<ChatMessageDTO.ToolCall> toolCalls = null;
        if (message instanceof AssistantMessage am && !am.getToolCalls().isEmpty()) {
            toolCalls = am.getToolCalls().stream()
                .map(tc -> new ChatMessageDTO.ToolCall(tc.id(), tc.name(), tc.arguments()))
                .toList();
        }

        List<ChatMessageDTO.ToolResponse> toolResponses = null;
        if (message instanceof ToolResponseMessage trm) {
            toolResponses = trm.getResponses().stream()
                .map(tr -> new ChatMessageDTO.ToolResponse(tr.id(), tr.name(), tr.responseData()))
                .toList();
        }

        return new ChatMessageDTO(role, message.getText(), metadata, toolCalls, toolResponses);
    }

    private static SessionDetailDTO.AnalysisRef toAnalysisRef(SessionAnalysis a) {
        return new SessionDetailDTO.AnalysisRef(
            a.getRunId(), a.getKind(), a.getQuery(),
            a.getStatus(), a.getStartedAt(), a.getCompletedAt());
    }

    private ImportJobResponse loadImport(String jobId) {
        try {
            return importJobService.getJob(UUID.fromString(jobId));
        } catch (Exception _) {
            return null;
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        Session session;
        try {
            session = sessionService.get(id);
        } catch (EntityNotFoundException _) {
            return ResponseEntity.notFound().build();
        }
        chatMemoryRepository.deleteByConversationId(session.getPrimaryChatId());
        sessionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
