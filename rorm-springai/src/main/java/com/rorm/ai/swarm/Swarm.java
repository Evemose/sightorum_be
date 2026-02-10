package com.rorm.ai.swarm;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatProgress;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.swarm.agents.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Main swarm orchestrator
 */
public class Swarm {

    private final ScoutSwarmAgent scout;
    private final PlannerSwarmAgent planner;
    private final ExecutorSwarmAgent executor;
    private final AnalyzerSwarmAgent analyzer;

    public Swarm(SwarmConfig config, AiChatService chatService, ChatProgress chatProgress) {
        var summarizer = new SecondarySwarmAgent(config.summarizer(), chatService, chatProgress, ThinkingLevel.NONE);

        this.scout = new ScoutSwarmAgent(
            new FirstLevelSwarmAgent(config.scout(), chatService, chatProgress, ThinkingLevel.HIGH),
            summarizer
        );

        var critic = new FirstLevelSwarmAgent(config.critic(), chatService, chatProgress, ThinkingLevel.HIGH);

        this.planner = new PlannerSwarmAgent(
            new FirstLevelSwarmAgent(config.planner(), chatService, chatProgress, ThinkingLevel.HIGH),
            critic,
            summarizer,
            new PlanValidator()
        );

        this.executor = new ExecutorSwarmAgent(
            new FirstLevelSwarmAgent(config.executor(), chatService, chatProgress, ThinkingLevel.MEDIUM),
            summarizer,
            new DependencyCoordinator()
        );

        this.analyzer = new AnalyzerSwarmAgent(
            new FirstLevelSwarmAgent(config.analyzer(), chatService, chatProgress, ThinkingLevel.HIGH),
            critic,
            summarizer
        );
    }

    public Flux<SwarmEvent> research(String input) {
        var eventSink = Sinks.many().multicast().<SwarmEvent>onBackpressureBuffer();

        Thread.ofVirtual().start(() -> {
            try {
                var scoutResult = scout.execute(input, eventSink);
                var plan = planner.negotiate(input, scoutResult, eventSink);
                var execResult = executor.execute(plan, eventSink);
                analyzer.negotiate(input, execResult, eventSink);
                eventSink.tryEmitComplete();
            } catch (Exception e) {
                eventSink.tryEmitError(e);
            }
        });

        return eventSink.asFlux();
    }
}