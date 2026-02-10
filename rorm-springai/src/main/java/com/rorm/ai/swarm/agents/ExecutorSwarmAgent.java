package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.BranchExecutionStarted;
import com.rorm.ai.swarm.SwarmEvent.StepExecutionFinished;
import com.rorm.ai.swarm.SwarmEvent.StepExecutionStarted;
import com.rorm.ai.swarm.dto.BranchExecutionResult;
import com.rorm.ai.swarm.dto.ResearchPlanDTO;
import com.rorm.ai.swarm.dto.ResearchPlanDTO.ResearchBranch;
import com.rorm.ai.swarm.dto.ResearchPlanDTO.ResearchStep;
import com.rorm.ai.swarm.dto.StepExecutionResult;
import com.rorm.ai.swarm.dto.StepRef;
import reactor.core.publisher.Sinks.Many;

import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;

/**
 * Executor agent - executes research plan steps with dependency coordination
 */
@SuppressWarnings("preview")
public class ExecutorSwarmAgent extends SwarmAgent {
    private final FirstLevelSwarmAgent executor;
    private final DependencyCoordinator coordinator;

    public ExecutorSwarmAgent(
        FirstLevelSwarmAgent executor,
        SecondarySwarmAgent summarizer,
        DependencyCoordinator coordinator
    ) {
        super(summarizer);
        this.executor = executor;
        this.coordinator = coordinator;
    }

    public List<BranchExecutionResult> execute(ResearchPlanDTO plan, Many<SwarmEvent> eventSink) {
        var dependentsMap = buildDependentsMap(plan);
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<BranchExecutionResult>allSuccessfulOrThrow())) {
            for (var branch : plan.branches()) {
                scope.fork(() -> executeBranch(branch, dependentsMap, eventSink));
            }
            return scope.join().map(StructuredTaskScope.Subtask::get).toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Research execution interrupted", e);
        }
    }

    private Map<StepRef, List<StepRef>> buildDependentsMap(ResearchPlanDTO plan) {
        return plan.branches().stream()
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

    private BranchExecutionResult executeBranch(
        ResearchBranch branch,
        Map<StepRef, List<StepRef>> dependentsMap,
        Many<SwarmEvent> eventSink
    ) {
        var branchSink = createTokenSink();
        eventSink.tryEmitNext(new BranchExecutionStarted(branchSink.asFlux()));

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<StepExecutionResult>allSuccessfulOrThrow())) {
            for (var step : branch.steps()) {
                scope.fork(() -> executeStep(branch.branchId(), step, dependentsMap, branchSink, eventSink));
            }
            scope.join();
            // TODO: enhance input
            return structurize(
                branchSink.asFlux().reduce(new StringBuffer(), StringBuffer::append).map(StringBuffer::toString).block(),
                BranchExecutionResult.class
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Branch execution interrupted", e);
        }
    }

    private StepExecutionResult executeStep(
        String branchId,
        ResearchStep step,
        Map<StepRef, List<StepRef>> dependentsMap,
        Many<String> branchSink,
        Many<SwarmEvent> eventSink
    ) throws InterruptedException {
        var stepRef = new StepRef(branchId, step.stepId());

        coordinator.awaitDependencies(stepRef, step.dependencies());

        var result = streamAndStructurize(
            StepParams.<StepExecutionResult>builder()
                .agent(executor)
                .userPrompt("Execute step " + stepRef + " with dependencies " + step.dependencies())
                .responseType(StepExecutionResult.class)
                .startEventFactory(StepExecutionStarted::new)
                .endEventFactory(StepExecutionFinished::new)
                .tokenConsumer(f -> f.subscribe(
                    branchSink::tryEmitNext,
                    branchSink::tryEmitError,
                    branchSink::tryEmitComplete
                )).build(),
            eventSink
        );
        coordinator.markComplete(stepRef, dependentsMap.getOrDefault(stepRef, List.of()));

        return result;
    }
}