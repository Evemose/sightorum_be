package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.*;
import com.rorm.ai.swarm.SwarmMind;
import com.rorm.ai.swarm.SwarmMindTool;
import com.rorm.ai.swarm.dto.BranchExecutionResultDTO;
import com.rorm.ai.swarm.dto.ResearchPlanDTO;
import com.rorm.ai.swarm.dto.ResearchPlanDTO.ResearchBranch;
import com.rorm.ai.swarm.dto.ResearchPlanDTO.ResearchStep;
import com.rorm.ai.swarm.dto.StepExecutionResultDTO;
import com.rorm.ai.swarm.dto.StepRef;
import com.rorm.ml.JobMetadataStore;
import com.rorm.ml.JobResultAwaiter;
import com.rorm.ml.stream.JobEvent;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Sinks.Many;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Executor agent - executes research plan steps with dependency coordination
 */
@Slf4j
@SuppressWarnings("preview")
public class ExecutorSwarmAgent extends SwarmAgent {

    private static final ScopedValue<ResearchPlanDTO> CURRENT_PLAN = ScopedValue.newInstance();
    private static final long JOB_TIMEOUT_MINUTES = 30;

    private final SwarmMind swarmMind;
    private final FirstLevelSwarmAgent executor;
    private final DependencyCoordinator coordinator;
    private final JobResultAwaiter jobResultAwaiter;
    private final JobMetadataStore jobMetadataStore;

    public ExecutorSwarmAgent(
        SwarmMind swarmMind,
        FirstLevelSwarmAgent executor,
        SecondarySwarmAgent summarizer,
        DependencyCoordinator coordinator,
        JobResultAwaiter jobResultAwaiter,
        JobMetadataStore jobMetadataStore
    ) {
        super(summarizer);
        this.swarmMind = swarmMind;
        this.executor = executor;
        this.coordinator = coordinator;
        this.jobResultAwaiter = jobResultAwaiter;
        this.jobMetadataStore = jobMetadataStore;
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
        var branchRawOutput = new StringBuffer();
        var branchId = branch.branchId();
        eventSink.tryEmitNext(new BranchExecutionStarted(branchId, branchSink.asFlux()));

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<StepExecutionResultDTO>allSuccessfulOrThrow())) {
            var steps = branch.steps();
            for (var i = 0; i < steps.size(); i++) {
                var step = steps.get(i);
                var previousStepId = i == 0 ? null : steps.get(i - 1).stepId();
                scope.fork(() -> executeStep(
                    branchId, step, previousStepId, dependentsMap, branchSink, branchRawOutput, eventSink
                ));
            }
            scope.join();
            branchSink.tryEmitComplete();
            // TODO: enhance input
            var result = structurize(branchRawOutput.toString(), BranchExecutionResultDTO.class);
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
        StringBuffer branchRawOutput,
        Many<SwarmEvent> eventSink
    ) throws InterruptedException {
        var stepRef = new StepRef(branchId, step.stepId());

        coordinator.awaitDependencies(stepRef, step.dependencies());

        var swarmTool = SwarmMindTool.forStep(CURRENT_PLAN.get(), stepRef, swarmMind);
        var launchedJobs = new CopyOnWriteArrayList<UUID>();
        var sb = new StringBuffer();

        // Phase 1: execute the step (LLM may launch jobs via MlTrainingTool)
        var phase1Result = streamAndStructurize(
            StepParams.<StepExecutionResultDTO>builder()
                .agent(executor)
                .userPrompt(buildStepPrompt(CURRENT_PLAN.get(), step))
                .responseType(StepExecutionResultDTO.class)
                .eventId(step.stepId())
                .startEventFactory((_, tokens) ->
                    new StepExecutionStarted(branchId, step.stepId(), previousStepId, step.dependencies(), tokens)
                )
                .endEventFactory((_, dto, raw) ->
                    new StepExecutionFinished(branchId, step.stepId(), previousStepId, step.dependencies(), dto, raw)
                )
                .requestBuilderCustomizer(b -> b
                    .withTool(swarmTool)
                    .withAdvisor(swarmMind.stepAdvisor())
                    .withToolContextEntry("researchPlanOverview", buildPlanOverview(CURRENT_PLAN.get()))
                    .withToolContextEntry("launchedJobs", launchedJobs)
                )
                .tokenConsumer(f -> f.subscribe(
                    token -> {
                        sb.append(token);
                        branchRawOutput.append(token);
                        branchSink.tryEmitNext(token);
                    },
                    branchSink::tryEmitError
                ))
                .build(),
            eventSink
        );

        if (launchedJobs.isEmpty()) {
            // No job launched — standard path
            swarmMind.storeStep(sb.toString(), stepRef, phase1Result);
            coordinator.markComplete(stepRef, dependentsMap.getOrDefault(stepRef, List.of()));
            return phase1Result;
        }

        // Job was launched — two-phase execution
        // Store partial result for RAG availability
        swarmMind.storeStep(sb.toString(), stepRef, phase1Result);

        // Emit per-job await-started, await each individually, emit completed
        var jobResults = awaitJobResults(branchId, step.stepId(), launchedJobs, eventSink);

        // Build phase-2 prompt with job results context
        var phase2Prompt = buildPhase2Prompt(CURRENT_PLAN.get(), sb.toString(), jobResults, launchedJobs);
        var phase2Sb = new StringBuffer();

        // Phase 2: interpret job results in context of phase-1 findings
        var phase2Result = streamAndStructurize(
            StepParams.<StepExecutionResultDTO>builder()
                .agent(executor)
                .userPrompt(phase2Prompt)
                .responseType(StepExecutionResultDTO.class)
                .eventId(step.stepId() + "-phase2")
                .startEventFactory((_, tokens) ->
                    new StepExecutionStarted(branchId, step.stepId() + "-phase2", step.stepId(), step.dependencies(), tokens)
                )
                .endEventFactory((_, dto, raw) ->
                    new StepExecutionFinished(branchId, step.stepId() + "-phase2", step.stepId(), step.dependencies(), dto, raw)
                )
                .requestBuilderCustomizer(b -> b
                    .withTool(swarmTool)
                    .withAdvisor(swarmMind.stepAdvisor())
                    .withToolContextEntry("researchPlanOverview", buildPlanOverview(CURRENT_PLAN.get()))
                )
                .tokenConsumer(f -> f.subscribe(
                    token -> {
                        phase2Sb.append(token);
                        branchRawOutput.append(token);
                        branchSink.tryEmitNext(token);
                    },
                    branchSink::tryEmitError
                ))
                .build(),
            eventSink
        );

        // Replace partial with final result
        swarmMind.storeStep(phase2Sb.toString(), stepRef, phase2Result);
        coordinator.markComplete(stepRef, dependentsMap.getOrDefault(stepRef, List.of()));
        return phase2Result;
    }

    private String buildStepPrompt(ResearchPlanDTO plan, ResearchStep step) {
        var sb = new StringBuilder();
        sb.append("## High-Level Plan Overview\n\n");
        sb.append(buildPlanOverview(plan));
        sb.append("\n\n");
        sb.append("## Step Assignment\n\n");
        sb.append("**Objective**: ").append(step.objective()).append("\n\n");
        sb.append("**Suggested Approach**: ").append(step.suggestedApproach()).append("\n\n");

        if (!step.dependencies().isEmpty()) {
            sb.append("**Dependencies**: ").append(step.dependencies()).append("\n\n");
        }

        sb.append("**Expected Outputs**: You must produce these variables:\n");
        for (var output : step.outputs()) {
            sb.append("- `").append(output.variableName()).append("` (")
                .append(output.type()).append("): ")
                .append(output.description()).append("\n");
        }

        return sb.toString();
    }

    private String buildPlanOverview(ResearchPlanDTO plan) {
        var sb = new StringBuilder();
        sb.append("**Goal**: ").append(plan.goal()).append("\n");
        sb.append("**Overall Complexity**: ").append(plan.totalComplexity()).append("\n");
        sb.append("**Success Criteria**:\n");
        for (var criterion : plan.successCriteria()) {
            sb.append("- ").append(criterion).append("\n");
        }

        sb.append("\n**Branches**:\n");
        for (var branch : plan.branches()) {
            sb.append("- Branch `").append(branch.branchId()).append("` (priority: ")
                .append(branch.priority()).append(", complexity: ")
                .append(branch.complexity()).append(")\n");
            sb.append("  Focus: ").append(branch.goal()).append("\n");
            sb.append("  Steps: ");
            for (var step : branch.steps()) {
                sb.append("    - Objective: ").append(step.objective()).append(" (stepId: ").append(step.stepId()).append(")\n");
                sb.append("      Outputs:\n");
                for (var output : step.outputs()) {
                    sb.append("        - `").append(output.variableName()).append("` (")
                        .append(output.type()).append("): ")
                        .append(output.description()).append("\n");
                }
            }
        }
        return sb.toString().stripTrailing();
    }

    private List<JobEvent> awaitJobResults(
        String branchId, String stepId, List<UUID> jobIds, Many<SwarmEvent> eventSink
    ) {
        var results = new ArrayList<JobEvent>(jobIds.size());

        for (var jobId : jobIds) {
            eventSink.tryEmitNext(new StepJobAwaitStarted(branchId, stepId, jobId));

            JobEvent event;
            try {
                event = jobResultAwaiter.await(jobId, JOB_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread interrupted while awaiting job", e);
            } finally {
                jobResultAwaiter.remove(jobId);
            }

            results.add(event);
            eventSink.tryEmitNext(new StepJobCompleted(branchId, stepId, jobId, event));
        }

        return results;
    }

    private String buildPhase2Prompt(
        ResearchPlanDTO plan,
        String phase1Output,
        List<JobEvent> jobResults,
        List<UUID> jobIds
    ) {
        var sb = new StringBuilder();
        sb.append("## High-Level Plan Overview\n\n");
        sb.append(buildPlanOverview(plan));
        sb.append("\n\n");
        sb.append("## Phase 1 Analysis Output\n\n");
        sb.append(phase1Output);
        sb.append("\n\n## Job Results\n\n");

        for (int i = 0; i < jobIds.size(); i++) {
            var jobId = jobIds.get(i);
            var event = jobResults.get(i);

            sb.append("### Job ").append(jobId).append("\n");

            // Add persisted context (reason, furtherInstructions)
            jobMetadataStore.findByJobId(jobId).ifPresent(info -> {
                sb.append("**Reason**: ").append(info.reason()).append("\n");
                sb.append("**Instructions**: ").append(info.furtherInstructions()).append("\n");
            });

            if (event == null) {
                sb.append("Status: TIMED OUT — job is still running.\n");
            } else if (event.isFailed()) {
                sb.append("Status: FAILED\n");
                sb.append("Error: ").append(event.error()).append("\n");
            } else if (event.isSuccess()) {
                sb.append("Status: SUCCESS\n");
                if (event.metrics() != null) {
                    sb.append("Metrics: ").append(event.metrics()).append("\n");
                }
                if (event.message() != null) {
                    sb.append("Message: ").append(event.message()).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("""
            ## Task
            
            Interpret the job results above in the context of the phase 1 analysis.
            Follow the instructions provided for each job.
            Incorporate the job metrics and outcomes into your analysis and provide updated findings.
            """);

        return sb.toString();
    }
}
