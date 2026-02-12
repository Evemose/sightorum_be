package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.BranchExecutionFinished;
import com.rorm.ai.swarm.SwarmEvent.BranchExecutionStarted;
import com.rorm.ai.swarm.SwarmEvent.StepExecutionFinished;
import com.rorm.ai.swarm.SwarmEvent.StepExecutionStarted;
import com.rorm.ai.swarm.SwarmMind;
import com.rorm.ai.swarm.SwarmMindTool;
import com.rorm.ai.swarm.dto.BranchExecutionResultDTO;
import com.rorm.ai.swarm.dto.ResearchPlanDTO;
import com.rorm.ai.swarm.dto.ResearchPlanDTO.ResearchBranch;
import com.rorm.ai.swarm.dto.ResearchPlanDTO.ResearchStep;
import com.rorm.ai.swarm.dto.StepExecutionResultDTO;
import com.rorm.ai.swarm.dto.StepRef;
import reactor.core.publisher.Sinks.Many;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;

/**
 * Executor agent - executes research plan steps with dependency coordination
 */
@SuppressWarnings("preview")
public class ExecutorSwarmAgent extends SwarmAgent {

    private static final ScopedValue<ResearchPlanDTO> CURRENT_PLAN = ScopedValue.newInstance();

    private final SwarmMind swarmMind;
    private final FirstLevelSwarmAgent executor;
    private final DependencyCoordinator coordinator;

    public ExecutorSwarmAgent(
        SwarmMind swarmMind,
        FirstLevelSwarmAgent executor,
        SecondarySwarmAgent summarizer,
        DependencyCoordinator coordinator
    ) {
        super(summarizer);
        this.swarmMind = swarmMind;
        this.executor = executor;
        this.coordinator = coordinator;
    }

    public List<BranchExecutionResultDTO> execute(ResearchPlanDTO plan, Many<SwarmEvent> eventSink) {
        return ScopedValue.where(CURRENT_PLAN, plan).call(() -> {
            var dependentsMap = buildDependentsMap();
            try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<BranchExecutionResultDTO>allSuccessfulOrThrow())) {
                for (var branch : plan.branches()) {
                    scope.fork(() -> executeBranch(branch, dependentsMap, eventSink));
                }
                return scope.join().map(StructuredTaskScope.Subtask::get).toList();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Research execution interrupted", e);
            }
        });
    }

    private Map<StepRef, List<StepRef>> buildDependentsMap() {
        return CURRENT_PLAN.get().branches().stream()
            .flatMap(branch -> branch.steps().stream()
                .flatMap(step -> step.dependencies().stream()
                    .map(dep -> Map.entry(dep, new StepRef(branch.branchId(), step.stepId())))
                )
            )
            .collect(Collectors.groupingBy(
                Map.Entry::getKey,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())
            ));
    }

    private BranchExecutionResultDTO executeBranch(
        ResearchBranch branch,
        Map<StepRef, List<StepRef>> dependentsMap,
        Many<SwarmEvent> eventSink
    ) {
        var branchSink = createTokenSink();
        var structuralBranchId = UUID.randomUUID().toString();
        eventSink.tryEmitNext(new BranchExecutionStarted(structuralBranchId, branchSink.asFlux()));

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<StepExecutionResultDTO>allSuccessfulOrThrow())) {
            for (var step : branch.steps()) {
                scope.fork(() -> executeStep(branch.branchId(), step, dependentsMap, branchSink, eventSink, structuralBranchId));
            }
            scope.join();
            // TODO: enhance input
            var result = structurize(
                branchSink.asFlux().reduce(new StringBuffer(), StringBuffer::append).map(StringBuffer::toString).block(),
                BranchExecutionResultDTO.class
            );
            eventSink.tryEmitNext(new BranchExecutionFinished(structuralBranchId, result, "Branch execution completed"));
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Branch execution interrupted", e);
        }
    }

    private StepExecutionResultDTO executeStep(
        String branchId,
        ResearchStep step,
        Map<StepRef, List<StepRef>> dependentsMap,
        Many<String> branchSink,
        Many<SwarmEvent> eventSink,
        String structuralBranchId
    ) throws InterruptedException {
        var stepRef = new StepRef(branchId, step.stepId());

        coordinator.awaitDependencies(stepRef, step.dependencies());

        var swarmTool = SwarmMindTool.forStep(CURRENT_PLAN.get(), stepRef, swarmMind);
        var sb = new StringBuffer();

        var result = streamAndStructurize(
            StepParams.<StepExecutionResultDTO>builder()
                .agent(executor)
                .userPrompt("Execute step " + stepRef + " with dependencies " + step.dependencies())
                .responseType(StepExecutionResultDTO.class)
                .startEventFactory((id, tokens) ->
                    new StepExecutionStarted(structuralBranchId, id, tokens)
                )
                .endEventFactory((id, dto, raw) ->
                    new StepExecutionFinished(structuralBranchId, id, dto, raw)
                )
                .requestBuilderCustomizer(b -> b.withTool(swarmTool).withAdvisor(swarmMind.stepAdvisor()))
                .tokenConsumer(f -> f.subscribe(
                    token -> {
                        sb.append(token);
                        branchSink.tryEmitNext(token);
                    },
                    branchSink::tryEmitError,
                    branchSink::tryEmitComplete
                ))
                .build(),
            eventSink
        );
        swarmMind.storeStep(sb.toString(), result);
        coordinator.markComplete(stepRef, dependentsMap.getOrDefault(stepRef, List.of()));

        return result;
    }
}