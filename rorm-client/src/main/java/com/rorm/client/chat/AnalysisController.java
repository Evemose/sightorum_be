package com.rorm.client.chat;

import com.rorm.client.chat.dto.AnalysisDetailDTO;
import com.rorm.client.chat.dto.AnalysisListItemDTO;
import com.rorm.client.chat.dto.NudgeResponse;
import com.rorm.client.chat.session.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/research")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final SessionService sessionService;

    @GetMapping
    public List<AnalysisListItemDTO> list() {
        return sessionService.findAllAnalyses().stream()
            .map(a -> new AnalysisListItemDTO(
                a.getRunId(),
                sessionService.findSchemaName(a.getSessionId()).orElse(null),
                a.getKind(),
                a.getStatus(),
                a.getQuery(),
                a.getStartedAt(),
                a.getCompletedAt()))
            .toList();
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
        @PathVariable String runId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        log.info("Research SSE connect: runId={}, lastEventId={}", runId, lastEventId);
        return analysisService.streamEvents(runId, lastEventId);
    }

    @PostMapping("/{runId}/nudge")
    public NudgeResponse nudge(@PathVariable String runId) {
        var resumed = analysisService.nudge(runId);
        log.info("Research nudge: runId={}, resumed={}", runId, resumed);
        return new NudgeResponse(runId, resumed);
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
