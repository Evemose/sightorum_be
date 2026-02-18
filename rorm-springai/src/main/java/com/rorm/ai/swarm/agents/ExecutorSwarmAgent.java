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

    public Map<String, BranchExecutionResultDTO> execute(ResearchPlanDTO plan, Many<SwarmEvent> eventSink) {
        return ScopedValue.where(CURRENT_PLAN, plan).call(() -> {
            var dependentsMap = buildDependentsMap();
            try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Map.Entry<String, BranchExecutionResultDTO>>allSuccessfulOrThrow()
            )) {
                for (var branch : plan.branches()) {
                    scope.fork(() -> executeBranch(branch, dependentsMap, eventSink));
                }
                return scope.join().map(StructuredTaskScope.Subtask::get).collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue
                ));
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

    private Map.Entry<String, BranchExecutionResultDTO> executeBranch(
        ResearchBranch branch,
        Map<StepRef, List<StepRef>> dependentsMap,
        Many<SwarmEvent> eventSink
    ) {
        var branchSink = createTokenSink();
        var branchId = branch.branchId();
        eventSink.tryEmitNext(new BranchExecutionStarted(branchId, branchSink.asFlux()));

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<StepExecutionResultDTO>allSuccessfulOrThrow())) {
            var steps = branch.steps();
            for (var i = 0; i < steps.size(); i++) {
                var step = steps.get(i);
                var previousStepId = i == 0 ? null : steps.get(i - 1).stepId();
                scope.fork(() -> executeStep(branchId, step, previousStepId, dependentsMap, branchSink, eventSink));
            }
            scope.join();
            // TODO: enhance input
            var result = structurize(
                branchSink.asFlux().reduce(new StringBuffer(), StringBuffer::append).map(StringBuffer::toString).block(),
                BranchExecutionResultDTO.class
            );
            eventSink.tryEmitNext(new BranchExecutionFinished(branchId, result, "Branch execution completed"));
            return Map.entry(branchId, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Branch execution interrupted", e);
        }
    }

    private StepExecutionResultDTO executeStep(
        String branchId,
        ResearchStep step,
        String previousStepId,
        Map<StepRef, List<StepRef>> dependentsMap,
        Many<String> branchSink,
        Many<SwarmEvent> eventSink
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
                .eventId(step.stepId())
                .startEventFactory((_, tokens) ->
                    new StepExecutionStarted(branchId, step.stepId(), previousStepId, step.dependencies(), tokens)
                )
                .endEventFactory((_, dto, raw) ->
                    new StepExecutionFinished(branchId, step.stepId(), previousStepId, step.dependencies(), dto, raw)
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
        swarmMind.storeStep(sb.toString(), stepRef, result);
        coordinator.markComplete(stepRef, dependentsMap.getOrDefault(stepRef, List.of()));

        return result;
    }
}
