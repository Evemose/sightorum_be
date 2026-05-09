package com.rorm.client.chat.mock;

import com.rorm.ai.swarm.SwarmEventBus;
import com.rorm.client.chat.dto.AnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Dev-only mock for {@code /research} that emits a scripted, branched
 * DurableSwarm event sequence to the live event bus. Subscribers pick the
 * events up via the existing {@code GET /research/{runId}/stream} SSE
 * endpoint, so a frontend can exercise its rendering against realistic
 * supervisor branches and peer Q&A traffic without the LLM pipeline.
 */
@Slf4j
@RestController
@RequestMapping("/research/mock")
@RequiredArgsConstructor
@Profile("dev")
public class MockSwarmController {

    private final SwarmEventBus eventBus;

    @PostMapping
    public AnalysisResponse start() {
        var runId = "mock-" + UUID.randomUUID();
        log.info("Mock swarm started: runId={}", runId);
        Thread.startVirtualThread(() -> emit(runId));
        return new AnalysisResponse(runId);
    }

    private void emit(String runId) {
        try {
            new MockSwarmScript(runId, eventBus).run();
        } catch (Exception e) {
            log.error("Mock swarm {} crashed", runId, e);
        } finally {
            eventBus.complete(runId);
        }
    }
}
