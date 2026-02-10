package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.NegotiationFinishReason;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.*;
import com.rorm.ai.swarm.dto.PlanCritiqueDTO;
import com.rorm.ai.swarm.dto.ResearchPlanDTO;
import com.rorm.ai.swarm.dto.ScoutOverviewDTO;
import com.rorm.ai.swarm.dto.StepRef;
import reactor.core.publisher.Sinks.Many;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Planner agent with critic negotiation loop
 */
public class PlannerSwarmAgent extends SwarmAgent {
    private final FirstLevelSwarmAgent planner;
    private final FirstLevelSwarmAgent critic;
    private final PlanValidator validator;

    public PlannerSwarmAgent(
        FirstLevelSwarmAgent planner,
        FirstLevelSwarmAgent critic,
        SecondarySwarmAgent summarizer,
        PlanValidator validator
    ) {
        super(summarizer);
        this.planner = planner;
        this.critic = critic;
        this.validator = validator;
    }

    public ResearchPlanDTO negotiate(String input, ScoutOverviewDTO scoutResult, Many<SwarmEvent> eventSink) {
        var tokenSink = createTokenSink();
        eventSink.tryEmitNext(new PlanNegotiationStarted(tokenSink.asFlux()));

        var scores = new ArrayList<Double>();
        var cycles = List.<List<StepRef>>of();

        for (var iteration = 0; ; iteration++) {
            tokenSink.tryEmitNext("Iteration " + (iteration + 1) + ":\nPlanner:\n\n");

            var draftPlan = createPlan(input, scoutResult, cycles, tokenSink, eventSink);
            cycles = validator.findCycles(draftPlan);

            if (!cycles.isEmpty()) {
                handleCycles(cycles, tokenSink);
                iteration--; // Don't count cycle iterations
                continue;
            }

            tokenSink.tryEmitNext("\nCritic:\n\n");
            var critique = critiquePlan(draftPlan, input, tokenSink, eventSink);
            scores.add(critique.planScore());

            var finishReason = shouldFinish(scores, iteration + 1);
            if (finishReason.isPresent()) {
                eventSink.tryEmitNext(new PlanNegotiationFinished(
                    draftPlan,
                    "Negotiation completed",
                    finishReason.get()
                ));
                tokenSink.tryEmitComplete();
                return draftPlan;
            }
        }
    }

    private ResearchPlanDTO createPlan(
        String input,
        ScoutOverviewDTO scoutResult,
        List<List<StepRef>> cycles,
        Many<String> tokenSink,
        Many<SwarmEvent> eventSink
    ) {
        var userPrompt = input + "\n\nScout's findings:\n" + scoutResult + buildCycleWarning(cycles);

        var plan = streamAndStructurize(
            StepParams.<ResearchPlanDTO>builder()
                .agent(planner)
                .userPrompt(userPrompt)
                .responseType(ResearchPlanDTO.class)
                .startEventFactory(PlanVersionCreationStarted::new)
                .endEventFactory(PlanVersionCreationFinished::new)
                .tokenSink(tokenSink)
                .build(),
            eventSink
        );

        return addImplicitDependencies(plan);
    }

    private void handleCycles(List<List<StepRef>> cycles, Many<String> tokenSink) {
        tokenSink.tryEmitNext("\nDetected cycles in the plan:\n" +
                              cycles.stream()
                                  .map(cycle -> cycle.stream()
                                      .map(ref -> "Branch " + ref.branchId() + " Step " + ref.stepId())
                                      .collect(Collectors.joining(" -> "))
                                  ).collect(Collectors.joining("\n"))
        );
        tokenSink.tryEmitNext(
            """
                
                As cycles indicate logical issues in the plan, \
                we force a re-plan without sending it to the critic.
                
                """
        );
    }

    private PlanCritiqueDTO critiquePlan(
        ResearchPlanDTO plan,
        String originalInput,
        Many<String> tokenSink,
        Many<SwarmEvent> eventSink
    ) {
        return streamAndStructurize(
            StepParams.<PlanCritiqueDTO>builder()
                .agent(critic)
                .userPrompt(originalInput + "\n\nDraft plan:\n" + plan)
                .responseType(PlanCritiqueDTO.class)
                .startEventFactory(PlanVersionCritiqueStarted::new)
                .endEventFactory(PlanVersionCritiqueFinished::new)
                .tokenSink(tokenSink)
                .build(),
            eventSink
        );
    }

    private Optional<NegotiationFinishReason> shouldFinish(List<Double> scores, int iteration) {
        var hardLimit = 5;
        var softLimit = 3;

        if (iteration >= hardLimit) {
            return Optional.of(NegotiationFinishReason.MAX_ITERATIONS_REACHED);
        }

        double threshold = 8.5 - 0.75 * iteration;
        if (scores.getLast() >= threshold && iteration < softLimit) {
            return Optional.of(NegotiationFinishReason.APPROVED);
        }

        if (recentVsOverallAvg(scores, iteration) < 0.3) {
            return Optional.of(NegotiationFinishReason.INSIGNIFICANT_IMPROVEMENT);
        }

        return Optional.empty();
    }

    private String buildCycleWarning(List<List<StepRef>> cycles) {
        if (cycles.isEmpty()) {
            return "";
        }

        return "\n\nWarning: Previous plan had cycles:\n" +
               cycles.stream()
                   .map(cycle -> cycle.stream()
                       .map(ref -> "Branch " + ref.branchId() + " Step " + ref.stepId())
                       .collect(Collectors.joining(" -> "))
                   ).collect(Collectors.joining("\n")) +
               "\nPlease revise the plan to remove these cycles.\n";
    }

    private ResearchPlanDTO addImplicitDependencies(ResearchPlanDTO plan) {
        return plan.withBranchesBy(branches ->
            branches.stream().map(branch ->
                branch.withStepsBy(steps ->
                    IntStream.range(0, steps.size()).mapToObj(i -> {
                        var step = steps.get(i);
                        if (i == 0) {
                            return step;
                        }

                        return step.withDependenciesBy(deps -> {
                            var newDeps = new ArrayList<>(deps);
                            newDeps.add(new StepRef(branch.branchId(), steps.get(i - 1).stepId()));
                            return newDeps;
                        });
                    }).toList()
                )
            ).toList()
        );
    }

    private double recentVsOverallAvg(List<Double> scores, int iteration) {
        if (iteration < 2) {
            return 1.0;
        }

        var recentAvg = (scores.get(iteration - 1) + scores.get(iteration)) / 2.0;
        var overallAvg = scores.stream().limit(iteration + 1).mapToDouble(Double::doubleValue).average().orElse(0);

        return recentAvg - overallAvg;
    }
}