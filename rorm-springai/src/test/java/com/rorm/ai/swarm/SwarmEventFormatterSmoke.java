package com.rorm.ai.swarm;

import com.rorm.ai.chat.StreamToken;
import com.rorm.ai.swarm.dto.DomainResearchDTO;
import com.rorm.ai.swarm.dto.HypothesisGenerationDTO;
import com.rorm.ai.swarm.dto.HypothesisGenerationDTO.Hypothesis;
import com.rorm.ai.swarm.dto.ScoutAnalysisDTO;
import com.rorm.ml.dto.PipelineSpecRequest;
import com.rorm.ml.dto.pipelinespec.*;
import com.rorm.ml.dto.pipelinespec.QualityGates.NuisanceR2Gates;
import com.rorm.ml.dto.pipelinespec.QualityGates.PlaceboGates;
import com.rorm.ml.dto.pipelinespec.QualityGates.SanityGates;
import com.rorm.ml.dto.pipelinespec.RangeChecks.VifConfig;
import com.rorm.ml.dto.pipelinespec.ResidualChecks.AutoCorrectionConfig;
import com.rorm.ml.dto.pipelinespec.ResidualChecks.FieldCorrelationCheck;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runnable smoke check for {@link SwarmEventFormatter}. Feeds a deterministic
 * sequence of fake {@link SwarmEvent}s and {@link StreamToken}s into the
 * formatter so you can visually verify rendering before burning real Anthropic
 * tokens through {@code AiChatRunner}.
 * <p>
 * Not a real assertion test — run it from the IDE or via
 * {@code ./gradlew :rorm-springai:test --tests SwarmEventFormatterSmoke} and
 * watch stdout.
 */
final class SwarmEventFormatterSmoke {

    private static final long TOKEN_DELAY_MS = 40;
    private static final long PAUSE_MS = 800;

    private static void runRecon(Sinks.Many<SwarmEvent> events) throws InterruptedException {
        log("INFO Starting recon phase");
        var scoutTokens = replaySink();
        var domainTokens = replaySink();
        var scoutId = EventId.root("scout", UUID.randomUUID());
        var domainId = EventId.root("domain-researcher", UUID.randomUUID());

        events.tryEmitNext(new SwarmEvent.DurableScoutStarted(scoutId, scoutTokens.asFlux()));
        events.tryEmitNext(new SwarmEvent.DomainResearcherStarted(domainId, domainTokens.asFlux()));

        var scoutThread = Thread.ofVirtual().start(() -> {
            streamThinking(scoutTokens,
                "Analyzing the metamodel for shipment tables. "
                + "Looking at relations between containers, vehicles, and cold nodes. "
                + "There are ~500k rows with 30 attributes including excursionFlag as the target.");
            streamTool(scoutTokens, "executeQuery");
            streamText(scoutTokens,
                "The containers table has 200 rows with insulation type, age, and manufacturer fields. "
                + "Vehicles have 150 rows with refrigeration model and cooling capacity.");
            scoutTokens.tryEmitComplete();
        });

        var domainThread = Thread.ofVirtual().start(() -> {
            streamThinking(domainTokens,
                "Searching external sources for cold chain pharma benchmarks and industry baselines.");
            streamServerTool(domainTokens, "web_search", "cold chain excursion rates 2026");
            streamText(domainTokens,
                "Industry benchmarks from FDA and USP suggest 8-12% temperature excursion rates "
                + "for last-mile pharmaceutical cold chain distribution. "
                + "Best-in-class operators achieve 3-5% with active monitoring.");
            domainTokens.tryEmitComplete();
        });

        scoutThread.join();
        domainThread.join();

        var scoutDto = new ScoutAnalysisDTO(
            List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(),
            "Cold chain dataset");
        var domainDto = new DomainResearchDTO(
            new DomainResearchDTO.DomainIdentification("Pharma cold chain", null, "HIGH", ""),
            List.of(), List.of(), List.of(), List.of(), null, List.of());
        events.tryEmitNext(new SwarmEvent.DurableScoutFinished(scoutId, scoutDto, "scout raw output"));
        events.tryEmitNext(new SwarmEvent.DomainResearcherFinished(domainId, domainDto, "domain raw output"));
    }

    private static void runGen(Sinks.Many<SwarmEvent> events) throws InterruptedException {
        log("INFO Starting gen phase for anchor=containers");
        var genTokens = replaySink();
        var genId = EventId.child("generator", UUID.randomUUID(), List.of(),
            Map.of("anchor", "containers"));

        events.tryEmitNext(new SwarmEvent.GeneratorStarted(genId, genTokens.asFlux()));

        var t = Thread.ofVirtual().start(() -> {
            streamThinking(genTokens,
                "Building hypothesis about container insulation type and excursion rate. "
                + "The anchor entity is containers with 200 rows. Key treatment candidates: "
                + "insulation type, age in months, manufacturer.");
            streamTool(genTokens, "analyzeExpression");
            streamText(genTokens,
                "H1: Older containers (>36 months) show 2.3x higher excursion rate. "
                + "H2: Vacuum insulated panel containers outperform expanded polystyrene by 40%. "
                + "H3: Manufacturer cohort effect is significant for batches before 2022.");
            genTokens.tryEmitComplete();
        });
        t.join();

        var genDto = new HypothesisGenerationDTO(
            List.of(
                Hypothesis.forAnchors("H1", "containerAgeMonths", "-> excursionFlag"),
                Hypothesis.forAnchors("H2", "insulationType", "-> excursionFlag"),
                Hypothesis.forAnchors("H3", "manufacturer", "-> excursionFlag")
            ),
            List.of(), List.of(), List.of(), List.of()
        );
        events.tryEmitNext(new SwarmEvent.GeneratorFinished(genId, genDto, "gen raw output"));
    }

    private static void runParallelCompile(Sinks.Many<SwarmEvent> events) throws InterruptedException {
        log("INFO Compiling 3 hypotheses in parallel");
        var rebuttalGhost = EventId.child("rebuttal", UUID.randomUUID(), List.of(),
            Map.of("anchor", "containers"));

        var compilers = new CompilerHandle[]{
            startCompiler(events, rebuttalGhost, "H1"),
            startCompiler(events, rebuttalGhost, "H2"),
            startCompiler(events, rebuttalGhost, "H3")
        };

        var threads = new Thread[compilers.length];
        for (var i = 0; i < compilers.length; i++) {
            var c = compilers[i];
            threads[i] = Thread.ofVirtual().start(() -> {
                streamThinking(c.tokens,
                    "Compiling pipeline for " + c.hypothesisId + ". Identifying backdoor adjustment set "
                    + "and checking d-separation constraints.");
                streamTool(c.tokens, "executeQuery");
                streamText(c.tokens,
                    "Adjustment set for " + c.hypothesisId + ": {preDepartureTempC, routeDriveHours, "
                    + "stopSequence, nodeRefrigHealthPct}. Treatment form: CATEGORICAL.");
                c.tokens.tryEmitComplete();
            });
        }

        for (var thread : threads) {
            thread.join();
        }

        for (var c : compilers) {
            var dto = PipelineSpecRequest.builder()
                .hypothesisId(c.hypothesisId)
                .treatment("containerAgeMonths")
                .outcome("excursionFlag")
                .treatmentForm(TreatmentForm.CATEGORICAL)
                .dataQuery(null)
                .expectedRowCount(0)
                .dagEdges("T->Y")
                .dsepThreshold(0.03)
                .adjustmentSet(List.of("W1", "W2"))
                .estimationVariants(List.of())
                .gates(new QualityGates(
                    new NuisanceR2Gates(0.0, 0.0, 0.0, 0.0, 0.0),
                    new SanityGates(1, 0.0, 0.0),
                    new PlaceboGates(0.0)))
                .sensitivity(new SensitivityConfig(List.of(), List.of(), null, List.of()))
                .residualChecks(new ResidualChecks(
                    List.of(),
                    new FieldCorrelationCheck(0.01, List.of()),
                    new AutoCorrectionConfig(1, 0.05),
                    List.of()))
                .rangeChecks(new RangeChecks(
                    new VifConfig(10.0, List.of()),
                    List.of(),
                    List.of()))
                .build();
            events.tryEmitNext(new SwarmEvent.CompilerFinished(c.id, dto, "compiler raw output"));
        }
    }

    private static CompilerHandle startCompiler(Sinks.Many<SwarmEvent> events,
                                                EventId parent, String hypothesisId) {
        var tokens = replaySink();
        var id = EventId.child("compiler", UUID.randomUUID(), List.of(parent),
            Map.of("anchor", "containers", "hypothesis", hypothesisId));
        events.tryEmitNext(new SwarmEvent.CompilerStarted(id, tokens.asFlux()));
        return new CompilerHandle(id, tokens, hypothesisId);
    }

    private static Many<StreamToken> replaySink() {
        return Sinks.many().replay().all();
    }

    private static void streamThinking(Many<StreamToken> sink, String text) {
        for (var word : splitForStream(text)) {
            sleep();
            sink.tryEmitNext(new StreamToken.Thinking(word));
        }
    }

    private static void streamText(Many<StreamToken> sink, String text) {
        for (var word : splitForStream(text)) {
            sleep();
            sink.tryEmitNext(new StreamToken.Text(word));
        }
    }

    private static void streamTool(Many<StreamToken> sink, String name) {
        sleep();
        sink.tryEmitNext(new StreamToken.ToolCall(name));
    }

    private static void streamServerTool(Many<StreamToken> sink, String name, String query) {
        sleep();
        sink.tryEmitNext(new StreamToken.ServerTool(name, query));
    }

    private static String[] splitForStream(String text) {
        var parts = text.split(" ");
        for (var i = 0; i < parts.length; i++) {
            parts[i] = parts[i] + " ";
        }
        return parts;
    }

    private static void sleep() {
        try {
            Thread.sleep(TOKEN_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String line) {
        System.out.println("2026-04-10T17:20:00.000Z "
                           + line + " --- [smoke] c.r.ai.swarm.Smoke : " + line);
    }

    @Test
    void smoke() throws InterruptedException {
        try (var formatter = new SwarmEventFormatter()) {
            var events = formatter.events();

            runRecon(events);
            Thread.sleep(PAUSE_MS);
            runGen(events);
            Thread.sleep(PAUSE_MS);
            runParallelCompile(events);
            Thread.sleep(1500);
        }
    }

    private record CompilerHandle(EventId id, Many<StreamToken> tokens, String hypothesisId) {}

}
