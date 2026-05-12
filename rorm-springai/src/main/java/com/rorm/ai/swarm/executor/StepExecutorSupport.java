package com.rorm.ai.swarm.executor;

import com.rorm.DurableRuntime;
import com.rorm.ai.ModelSpaceResolver;
import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.MemoryInclude;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.*;
import com.rorm.ai.swarm.agents.*;
import com.rorm.ai.swarm.communication.*;
import com.rorm.metamodel.ModelSpace;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Shared execution logic for all first-level executor beans: streams
 * tokens, publishes them to the {@link SwarmEventBus}, accumulates raw
 * text, and hands the result to a {@link SecondarySwarmAgent} that
 * produces the typed DTO. The default secondary agent is a
 * {@link SummarizingSecondaryAgent} backed by the configured summarizer;
 * callers (e.g. the compiler executor) may pass a custom strategy that
 * reads from a tool-populated holder and re-streams the first-level
 * agent through {@link StreamPrimitive} when the contract is unmet.
 * <p>
 * Two cross-cutting concerns are wired here so every step inherits them:
 * <ul>
 *   <li>{@link SwarmContext} registration: each step picks a stable
 *       {@code chatId} (the input's, or one derived from its event
 *       token), registers itself in the context with its role and agent
 *       config, and attaches a {@link SwarmContextStreamAdvisor} to the
 *       streaming chat call so the context's at-the-moment state is
 *       populated as tokens flow.</li>
 *   <li>Tool-context entries ({@code swarmRunId},
 *       {@code swarmAskerChatId}, {@code swarmAskerRole},
 *       {@code swarmPipelineSpecHolder}) so swarm tools can locate the
 *       run, self-exclude, and deposit step-scoped artifacts.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class StepExecutorSupport {

    private final AiChatService chatService;
    private final PromptPlaceholders promptPlaceholders;
    private final DurableSwarmConfig config;
    private final SwarmEventBus eventBus;
    private final ModelSpaceResolver modelSpaceResolver;
    private final SwarmContext swarmContext;
    private final ToolCallRegistry toolCallRegistry;
    private final AgentCoalescingGate coalescingGate;
    private final DurableRuntime runtime;

    public <T> StepOutput<T> execute(StepExecutionInput input, AgentModelConfig agentConfig,
                                     Class<T> responseType) {
        var modelSpace = modelSpaceResolver.resolve(input.schema());
        return execute(input, agentConfig,
            new SummarizingSecondaryAgent<>(
                config.summarizer(), chatService, promptPlaceholders, modelSpace, responseType));
    }

    public <T> StepOutput<T> execute(StepExecutionInput input, AgentModelConfig agentConfig,
                                     SecondarySwarmAgent<T> secondaryAgent) {
        var kind = input.eventId().kind();
        eventBus.publish(input.runId(), new SwarmStreamEvent.AgentStarted(input.eventId(), kind));
        var effective = input.systemPromptOverride() != null
            ? agentConfig.withSystemPrompt(input.systemPromptOverride())
            : agentConfig;
        var chatId = chatIdFor(input);
        swarmContext.register(new AgentRegistration(
            input.runId(), chatId,
            input.eventId().token(),
            input.eventId().parents().stream().map(EventId::token).toList(),
            kind, input.schema(), effective, input.userPrompt()));
        return coalescingGate.gate(kind, firstTokenSignal ->
            runAndPublish(input, effective, chatId, secondaryAgent, firstTokenSignal));
    }

    private static String chatIdFor(StepExecutionInput input) {
        return input.chatId() != null ? input.chatId() : "swarm-step-" + input.eventId().token();
    }

    private <T> StepOutput<T> runAndPublish(StepExecutionInput input, AgentModelConfig effective,
                                            String chatId, SecondarySwarmAgent<T> secondaryAgent,
                                            Runnable firstTokenSignal) {
        var modelSpace = modelSpaceResolver.resolve(input.schema());
        var pipelineHolder = new PipelineSpecHolder();
        var streamPrimitive = streamPrimitiveFor(
            new StreamSetup(input, effective, modelSpace, chatId, pipelineHolder, firstTokenSignal));
        var raw = streamPrimitive.stream(input.userPrompt());
        var result = secondaryAgent.produce(new SecondaryAgentContext(
            input, chatId, raw, modelSpace, effective, streamPrimitive, pipelineHolder,
            toolCallRegistry));
        eventBus.publish(input.runId(), new SwarmStreamEvent.AgentFinished(
            input.eventId(), input.eventId().kind(), result.raw(), result.dto()));
        return new StepOutput<>(input.eventId(), result.dto(), result.raw());
    }

    private StreamPrimitive streamPrimitiveFor(StreamSetup setup) {
        var agent = new FirstLevelSwarmAgent(
            setup.agentConfig(), chatService, setup.input().schema(), setup.modelSpace(),
            promptPlaceholders);
        var advisors = buildStreamAdvisors(setup.input(), setup.chatId());
        var seq = new AtomicInteger();
        var firstSignaled = new java.util.concurrent.atomic.AtomicBoolean();
        var rt = new StreamRuntime(setup, agent, advisors, seq, firstSignaled);
        return userPrompt -> streamRaw(rt, userPrompt);
    }

    private String streamRaw(StreamRuntime rt, String userPrompt) {
        var input = rt.setup().input();
        var runId = input.runId();
        var eventId = input.eventId();
        return rt.agent().streamTokens(userPrompt,
                customizerFor(input, rt.setup().chatId(),
                    rt.setup().pipelineHolder(), rt.advisors()))
            .doOnNext(token -> {
                if (rt.firstSignaled().compareAndSet(false, true)) {
                    rt.setup().firstTokenSignal().run();
                }
                eventBus.publish(runId,
                    new SwarmStreamEvent.AgentToken(eventId, rt.seq().getAndIncrement(), token));
            })
            .reduce(new StringBuilder(), (sb, token) -> sb.append(token.toText()))
            .map(StringBuilder::toString)
            .block();
    }

    private record StreamSetup(
        StepExecutionInput input,
        AgentModelConfig agentConfig,
        ModelSpace modelSpace,
        String chatId,
        PipelineSpecHolder pipelineHolder,
        Runnable firstTokenSignal
    ) {}

    private List<Advisor> buildStreamAdvisors(StepExecutionInput input, String chatId) {
        return List.of(
            new SwarmContextStreamAdvisor(swarmContext, input.runId(), chatId),
            new ProgressReportAdvisor(
                runtime, input.runId(), input.eventId(), input.schema(), config.progressCharacter())
        );
    }

    private record StreamRuntime(
        StreamSetup setup,
        FirstLevelSwarmAgent agent,
        List<Advisor> advisors,
        AtomicInteger seq,
        java.util.concurrent.atomic.AtomicBoolean firstSignaled
    ) {}

    private UnaryOperator<ChatRequest.Builder> customizerFor(
        StepExecutionInput input, String chatId, PipelineSpecHolder pipelineHolder,
        List<Advisor> advisors
    ) {
        return b -> {
            b = b.withChatId(chatId)
                .withToolContextEntry(SwarmToolContext.RUN_ID_KEY, input.runId())
                .withToolContextEntry(SwarmToolContext.ASKER_EVENT_ID_KEY, input.eventId())
                .withToolContextEntry(SwarmToolContext.ASKER_CHAT_ID_KEY, chatId)
                .withToolContextEntry(SwarmToolContext.PIPELINE_SPEC_HOLDER_KEY, pipelineHolder)
                .withToolContextEntry(SwarmToolContext.TOOL_CALL_REGISTRY_KEY, toolCallRegistry);
            for (var advisor : advisors) {
                b = b.withAdvisor(advisor);
            }
            if (input.memoryIncludes() != null && !input.memoryIncludes().isEmpty()) {
                b = b.withMemoryIncludes(input.memoryIncludes().toArray(MemoryInclude[]::new));
            }
            return applyToolContextEntries(b, input);
        };
    }

    private static ChatRequest.Builder applyToolContextEntries(ChatRequest.Builder b,
                                                               StepExecutionInput input) {
        if (input.toolContextEntries() == null) {
            return b;
        }
        for (var entry : input.toolContextEntries().entrySet()) {
            b = b.withToolContextEntry(entry.getKey(), entry.getValue());
        }
        return b;
    }
}
