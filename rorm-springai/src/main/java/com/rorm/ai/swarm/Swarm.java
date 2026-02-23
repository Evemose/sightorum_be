package com.rorm.ai.swarm;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.swarm.agents.*;
import com.rorm.metamodel.ModelSpace;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.UUID;

/**
 * Main swarm orchestrator
 */
public class Swarm {

    private final ScoutSwarmAgent scout;
    private final PlannerSwarmAgent planner;
    private final ExecutorSwarmAgent executor;
    private final AnalyzerSwarmAgent analyzer;

    public Swarm(SwarmConfig config, AiChatService chatService, String schema, ModelSpace modelSpace, VectorStore vectorStore) {
        var summarizer = new SecondarySwarmAgent(config.summarizer(), chatService, schema, modelSpace, ThinkingLevel.NONE);
        var swarmMind = new SwarmMind(UUID.randomUUID().toString(), vectorStore);

        this.scout = new ScoutSwarmAgent(
            new FirstLevelSwarmAgent(config.scout(), chatService, schema, modelSpace, ThinkingLevel.HIGH),
            summarizer
        );

        var critic = new FirstLevelSwarmAgent(config.critic(), chatService, schema, modelSpace, ThinkingLevel.HIGH);

        this.planner = new PlannerSwarmAgent(
            new FirstLevelSwarmAgent(config.planner(), chatService, schema, modelSpace, ThinkingLevel.HIGH),
            critic,
            summarizer,
            new PlanValidator()
        );

        this.executor = new ExecutorSwarmAgent(
            swarmMind,
            new FirstLevelSwarmAgent(config.executor(), chatService, schema, modelSpace, ThinkingLevel.MEDIUM),
            summarizer,
            new DependencyCoordinator()
        );

        this.analyzer = new AnalyzerSwarmAgent(
            swarmMind,
            new FirstLevelSwarmAgent(config.analyzer(), chatService, schema, modelSpace, ThinkingLevel.HIGH),
            critic,
            summarizer
        );
    }

    public Flux<SwarmEvent> research(String input) {
        var eventSink = Sinks.many().replay().<SwarmEvent>all();

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
