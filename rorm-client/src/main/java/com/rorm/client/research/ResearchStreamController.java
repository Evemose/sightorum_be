package com.rorm.client.research;

import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/research/{id}/stream")
@RequiredArgsConstructor
public class ResearchStreamController {

    private final SseEmitterRegistry registry;
    private final ResearchService researchService;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(
        @PathVariable UUID id,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        researchService.assertResearchExists(id);
        log.info("New SSE connection for research progress: id={}, lastEventId={}", id, lastEventId);
        return registry.register("research:" + id, lastEventId);
    }
}
