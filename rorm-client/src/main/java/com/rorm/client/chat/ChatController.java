package com.rorm.client.chat;

import com.rorm.client.chat.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/chat/sessions")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionService sessionService;

    @PostMapping
    public ResponseEntity<ChatSessionResponse> createSession(
        @Valid @RequestBody CreateSessionRequest request
    ) {
        var session = sessionService.createSession(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @GetMapping
    public ResponseEntity<List<ChatSessionResponse>> listSessions() {
        return ResponseEntity.ok(sessionService.listSessions());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChatSessionResponse> getSession(@PathVariable UUID id) {
        return ResponseEntity.ok(sessionService.getSession(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable UUID id) {
        sessionService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/tree")
    public ResponseEntity<ChatBranchDTO> getChatTree(@PathVariable UUID id) {
        return ResponseEntity.ok(sessionService.getChatTree(id));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<Void> sendMessage(
        @PathVariable UUID id,
        @Valid @RequestBody SendMessageRequest request
    ) {
        sessionService.sendMessage(id, request);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/corrections")
    public ResponseEntity<Void> sendCorrection(
        @PathVariable UUID id,
        @Valid @RequestBody CorrectionRequest request
    ) {
        sessionService.sendCorrection(id, request);
        return ResponseEntity.accepted().build();
    }
}
