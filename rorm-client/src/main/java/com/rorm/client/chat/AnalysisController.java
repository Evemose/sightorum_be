package com.rorm.client.chat;

import com.rorm.client.chat.dto.AnalysisDetailDTO;
import com.rorm.client.chat.dto.AnalysisRequest;
import com.rorm.client.chat.dto.AnalysisResponse;
import com.rorm.client.chat.session.SessionService;
import com.rorm.client.utils.WithSchema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/research")
@RequiredArgsConstructor
@Validated
public class AnalysisController {

    private final AnalysisService analysisService;
    private final SessionService sessionService;

    @WithSchema("schema")
    @PostMapping
    public AnalysisResponse startAnalysis(
        @RequestParam @NotBlank String schema,
        @Valid @RequestBody AnalysisRequest request
    ) {
        var runId = analysisService.startAnalysis(schema, request);
        log.info("Analysis started: schema={}, runId={}", schema, runId);
        return new AnalysisResponse(runId);
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
        @PathVariable String runId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        log.info("Research SSE connect: runId={}, lastEventId={}", runId, lastEventId);
        return analysisService.streamEvents(runId, lastEventId);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<AnalysisDetailDTO> get(@PathVariable String runId) {
        return sessionService.findAnalysis(runId)
            .map(a -> ResponseEntity.ok(new AnalysisDetailDTO(
                a.getRunId(),
                a.getSessionId(),
                a.getKind(),
                a.getQuery(),
                a.getStatus(),
                a.getStartedAt(),
                a.getCompletedAt(),
                a.getErrorMessage()
            )))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
