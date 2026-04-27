package com.rorm.ai.swarm.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableFuture;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.swarm.*;
import com.rorm.ai.swarm.dto.StandoffArgumentDTO;
import com.rorm.ai.swarm.executor.StepExecutionInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Advocate vs prosecutor standoff phase. Submits two independent durable
 * invocations (advocate and prosecutor) whose system prompts share an
 * identical {@code <--CACHE[5m]-->}-anchored prefix containing the hypothesis,
 * pipeline, and skeptic materials. They diverge only after the cache
 * breakpoint, where each side's role definition follows.
 * <p>
 * Hard timing constraint: the prosecutor submission <b>must</b> wait until
 * the advocate has emitted its first streamed token. Anthropic writes the
 * prefix cache during prompt processing, which completes before the first
 * generated token. Gating the prosecutor on that signal guarantees the
 * prosecutor request is a cache read rather than a parallel cache write.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StandoffPhase {

    private static final TypeReference<StepOutput<StandoffArgumentDTO>> ARG_REF = new TypeReference<>() {};
    private static final Duration FIRST_TOKEN_TIMEOUT = Duration.ofMinutes(10);

    private final DurableRuntime runtime;
    private final ObjectMapper mapper;
    private final DurableSwarmConfig config;
    private final SwarmEventBus eventBus;

    public Output run(PipelineContext pipeCtx, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doRun(pipeCtx));
    }

    private Output doRun(PipelineContext pipeCtx) {
        var hypoCtx = pipeCtx.hypothesis();
        var advocateId = sideId("advocate", pipeCtx);
        var prosecutorId = sideId("prosecutor", pipeCtx);

        var advocateInput = inputFor(advocateId, pipeCtx, config.advocate().systemPrompt(),
            config.advocate().userPromptTemplate());
        var prosecutorInput = inputFor(prosecutorId, pipeCtx, config.prosecutor().systemPrompt(),
            config.prosecutor().userPromptTemplate());

        var firstAdvocateToken = new CompletableFuture<Void>();
        var subscription = subscribeForFirstToken(PhaseScope.runId(), advocateId, firstAdvocateToken);

        log.info("[swarm] Standoff for {}", hypoCtx.hypothesisId());
        try {
            var advocateFuture = runtime.submitAsync(
                "advocateExecutor-" + advocateId.token(),
                new JobSpec("advocateExecutor", "execute",
                    new Object[]{advocateInput},
                    new String[]{StepExecutionInput.class.getName()}));
            awaitFirstAdvocateToken(firstAdvocateToken);
            var prosecutorFuture = runtime.submitAsync(
                "prosecutorExecutor-" + prosecutorId.token(),
                new JobSpec("prosecutorExecutor", "execute",
                    new Object[]{prosecutorInput},
                    new String[]{StepExecutionInput.class.getName()}));
            DurableFuture.all(advocateFuture, prosecutorFuture).await();
            return new Output(
                mapper.convertValue(advocateFuture.await(), ARG_REF),
                mapper.convertValue(prosecutorFuture.await(), ARG_REF)
            );
        } finally {
            subscription.dispose();
        }
    }

    private EventId sideId(String kind, PipelineContext pipeCtx) {
        var hypoCtx = pipeCtx.hypothesis();
        return EventId.child(kind,
            ContentHash.of(Map.of(
                "kind", kind,
                "schema", hypoCtx.anchor().swarm().schema(),
                "hypothesisSpec", hypoCtx.gen().rebuttal().rawResponse(),
                "pipelineSpec", writeJson(pipeCtx.compile().compiler().dto()),
                "pipelineResult", writeJson(pipeCtx.compile().pipelineResult().metrics()),
                "skepticReport", pipeCtx.compile().scepticReview().rawResponse())),
            List.of(pipeCtx.compile().scepticReview().id()),
            Map.of("anchor", hypoCtx.anchor().anchorTag(), "hypothesis", hypoCtx.hypothesisId()));
    }

    private StepExecutionInput inputFor(EventId id, PipelineContext pipeCtx,
                                        String systemPromptTemplate, String userPrompt) {
        var anchor = pipeCtx.hypothesis().anchor();
        var renderedSystem = renderSystemPrompt(systemPromptTemplate, pipeCtx);
        return new StepExecutionInput(id, userPrompt,
            anchor.swarm().schema(),
            PhaseScope.runId(), null, null, null, renderedSystem);
    }

    private Disposable subscribeForFirstToken(String runId, EventId advocateId, CompletableFuture<Void> gate) {
        return eventBus.subscribe(runId)
            .filter(e -> e instanceof SwarmStreamEvent.AgentToken t && t.eventId().equals(advocateId))
            .next()
            .subscribe(_ -> gate.complete(null),
                err -> gate.completeExceptionally(err));
    }

    private static void awaitFirstAdvocateToken(CompletableFuture<Void> gate) {
        try {
            gate.get(FIRST_TOKEN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                "Advocate did not emit a first token within " + FIRST_TOKEN_TIMEOUT
                + " — prosecutor gating cannot release without breaking cache-reuse guarantee", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for advocate first token", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed waiting for advocate first token", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private String renderSystemPrompt(String template, PipelineContext pipeCtx) {
        var hypoCtx = pipeCtx.hypothesis();
        return template
            .replace("{{HYPOTHESIS}}", hypoCtx.gen().rebuttal().rawResponse())
            .replace("{{DOMAIN_RESEARCH}}", hypoCtx.anchor().recon().domain().rawResponse())
            .replace("{{SCOUT_RESULT}}", hypoCtx.anchor().recon().scout().rawResponse())
            .replace("{{PIPELINE_SPEC}}", writeJson(pipeCtx.compile().compiler().dto()))
            .replace("{{PIPELINE_RESULT}}", writeJson(pipeCtx.compile().pipelineResult().metrics()))
            .replace("{{SKEPTIC_REPORT}}", pipeCtx.compile().scepticReview().rawResponse());
    }

    public record Output(
        StepOutput<StandoffArgumentDTO> advocate,
        StepOutput<StandoffArgumentDTO> prosecutor
    ) {}
}
