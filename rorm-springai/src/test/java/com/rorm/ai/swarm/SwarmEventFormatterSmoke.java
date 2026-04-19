package com.rorm.ai.swarm;

import com.rorm.ai.chat.StreamToken;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runnable smoke check for {@link SwarmEventFormatter}. Feeds a deterministic
 * sequence of fake {@link SwarmStreamEvent}s into the formatter so you can
 * visually verify rendering before burning real Anthropic tokens through
 * {@code AiChatRunner}.
 * <p>
 * Not a real assertion test — run it from the IDE or via
 * {@code ./gradlew :rorm-springai:test --tests SwarmEventFormatterSmoke} and
 * watch stdout.
 */
final class SwarmEventFormatterSmoke {

    private static final long TOKEN_DELAY_MS = 40;
    private static final long PAUSE_MS = 800;

    private static void runRecon(Sinks.Many<SwarmStreamEvent> events) throws InterruptedException {
        log("INFO Starting recon phase");
        var scoutId = EventId.root("scout", UUID.randomUUID());
        var domainId = EventId.root("domain-researcher", UUID.randomUUID());

        events.tryEmitNext(new SwarmStreamEvent.AgentStarted(scoutId, "scout"));
        events.tryEmitNext(new SwarmStreamEvent.AgentStarted(domainId, "domain-researcher"));

        var scoutThread = Thread.ofVirtual().start(() -> {
            streamThinking(events, scoutId,
                "Analyzing the metamodel for shipment tables. "
                + "Looking at relations between containers, vehicles, and cold nodes. "
                + "There are ~500k rows with 30 attributes including excursionFlag as the target.");
            streamTool(events, scoutId, "executeQuery");
            streamText(events, scoutId,
                "The containers table has 200 rows with insulation type, age, and manufacturer fields. "
                + "Vehicles have 150 rows with refrigeration model and cooling capacity.");
        });

        var domainThread = Thread.ofVirtual().start(() -> {
            streamThinking(events, domainId,
                "Searching external sources for cold chain pharma benchmarks and industry baselines.");
            streamServerTool(events, domainId, "web_search", "cold chain excursion rates 2026");
            streamText(events, domainId,
                "Industry benchmarks from FDA and USP suggest 8-12% temperature excursion rates "
                + "for last-mile pharmaceutical cold chain distribution. "
                + "Best-in-class operators achieve 3-5% with active monitoring.");
        });

        scoutThread.join();
        domainThread.join();

        events.tryEmitNext(new SwarmStreamEvent.AgentFinished(scoutId, "scout", "scout raw output"));
        events.tryEmitNext(new SwarmStreamEvent.AgentFinished(domainId, "domain-researcher", "domain raw output"));
    }

    private static void runGen(Sinks.Many<SwarmStreamEvent> events) throws InterruptedException {
        log("INFO Starting gen phase for anchor=containers");
        var genId = EventId.child("generator", UUID.randomUUID(), List.of(),
            Map.of("anchor", "containers"));

        events.tryEmitNext(new SwarmStreamEvent.AgentStarted(genId, "generator"));

        var t = Thread.ofVirtual().start(() -> {
            streamThinking(events, genId,
                "Building hypothesis about container insulation type and excursion rate. "
                + "The anchor entity is containers with 200 rows. Key treatment candidates: "
                + "insulation type, age in months, manufacturer.");
            streamTool(events, genId, "analyzeExpression");
            streamText(events, genId,
                "H1: Older containers (>36 months) show 2.3x higher excursion rate. "
                + "H2: Vacuum insulated panel containers outperform expanded polystyrene by 40%. "
                + "H3: Manufacturer cohort effect is significant for batches before 2022.");
        });
        t.join();

        events.tryEmitNext(new SwarmStreamEvent.AgentFinished(genId, "generator", "gen raw output"));
    }

    private static void runParallelCompile(Sinks.Many<SwarmStreamEvent> events) throws InterruptedException {
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
                streamThinking(events, c.id,
                    "Compiling pipeline for " + c.hypothesisId + ". Identifying backdoor adjustment set "
                    + "and checking d-separation constraints.");
                streamTool(events, c.id, "executeQuery");
                streamText(events, c.id,
                    "Adjustment set for " + c.hypothesisId + ": {preDepartureTempC, routeDriveHours, "
                    + "stopSequence, nodeRefrigHealthPct}. Treatment form: CATEGORICAL.");
            });
        }

        for (var thread : threads) {
            thread.join();
        }

        for (var c : compilers) {
            events.tryEmitNext(new SwarmStreamEvent.AgentFinished(c.id, "compiler", "compiler raw output"));
        }
    }

    private static CompilerHandle startCompiler(Sinks.Many<SwarmStreamEvent> events,
                                                EventId parent, String hypothesisId) {
        var id = EventId.child("compiler", UUID.randomUUID(), List.of(parent),
            Map.of("anchor", "containers", "hypothesis", hypothesisId));
        events.tryEmitNext(new SwarmStreamEvent.AgentStarted(id, "compiler"));
        return new CompilerHandle(id, hypothesisId);
    }

    private static void streamThinking(Sinks.Many<SwarmStreamEvent> events, EventId id, String text) {
        for (var word : splitForStream(text)) {
            sleep();
            events.tryEmitNext(new SwarmStreamEvent.AgentToken(id, new StreamToken.Thinking(word)));
        }
    }

    private static void streamText(Sinks.Many<SwarmStreamEvent> events, EventId id, String text) {
        for (var word : splitForStream(text)) {
            sleep();
            events.tryEmitNext(new SwarmStreamEvent.AgentToken(id, new StreamToken.Text(word)));
        }
    }

    private static void streamTool(Sinks.Many<SwarmStreamEvent> events, EventId id, String name) {
        sleep();
        events.tryEmitNext(new SwarmStreamEvent.AgentToken(id, new StreamToken.ToolCall(name)));
    }

    private static void streamServerTool(Sinks.Many<SwarmStreamEvent> events, EventId id,
                                         String name, String query) {
        sleep();
        events.tryEmitNext(new SwarmStreamEvent.AgentToken(id, new StreamToken.ServerTool(name, query)));
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
        Sinks.Many<SwarmStreamEvent> events = Sinks.many().multicast().onBackpressureBuffer();
        try (var formatter = new SwarmEventFormatter(events.asFlux())) {
            runRecon(events);
            Thread.sleep(PAUSE_MS);
            runGen(events);
            Thread.sleep(PAUSE_MS);
            runParallelCompile(events);
            Thread.sleep(1500);
            events.tryEmitComplete();
        }
    }

    private record CompilerHandle(EventId id, String hypothesisId) {}
}
