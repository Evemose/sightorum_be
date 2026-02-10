package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.dto.ResearchPlanDTO;
import com.rorm.ai.swarm.dto.ResearchPlanDTO.ResearchBranch;
import com.rorm.ai.swarm.dto.ResearchPlanDTO.ResearchStep;
import com.rorm.ai.swarm.dto.StepRef;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates research plans for structural correctness
 */
public class PlanValidator {

    List<List<StepRef>> findCycles(ResearchPlanDTO plan) {
        var executedSteps = new HashSet<ResearchStep>();
        var allSteps = plan.branches().stream()
            .flatMap(b -> b.steps().stream())
            .collect(Collectors.toCollection(HashSet::new));

        var stepToBranchMap = plan.branches().stream()
            .flatMap(b -> b.steps().stream().map(s -> Map.entry(s, b)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // Remove all executable steps
        var availableSteps = findAvailableSteps(executedSteps, stepToBranchMap);
        while (!availableSteps.isEmpty()) {
            executedSteps.addAll(availableSteps);
            allSteps.removeAll(availableSteps);
            availableSteps = findAvailableSteps(executedSteps, stepToBranchMap);
        }

        // Remaining steps form cycles
        if (allSteps.isEmpty()) {
            return List.of();
        }

        return computeCycles(allSteps, stepToBranchMap);
    }

    private Set<ResearchStep> findAvailableSteps(
        Set<ResearchStep> executedSteps,
        Map<ResearchStep, ResearchBranch> stepToBranchMap
    ) {
        var executedStepRefs = executedSteps.stream()
            .map(s -> new StepRef(stepToBranchMap.get(s).branchId(), s.stepId()))
            .collect(Collectors.toSet());

        return stepToBranchMap.keySet().stream()
            .filter(researchStep -> !executedSteps.contains(researchStep))
            .filter(researchStep -> executedStepRefs.containsAll(researchStep.dependencies()))
            .collect(Collectors.toSet());
    }

    private List<List<StepRef>> computeCycles(
        Set<ResearchStep> cyclicSteps,
        Map<ResearchStep, ResearchBranch> stepToBranchMap
    ) {
        var stepRefToStepMap = cyclicSteps.stream()
            .collect(Collectors.toMap(
                s -> new StepRef(stepToBranchMap.get(s).branchId(), s.stepId()),
                Function.identity()
            ));

        return cyclicSteps.stream()
            .flatMap(step -> step.dependencies().stream()
                .map(dep -> findCycleByTracing(stepRefToStepMap, dep, new HashSet<>()))
                .filter(cycle -> !cycle.isEmpty())
            )
            .distinct()
            .toList();
    }

    private List<StepRef> findCycleByTracing(
        Map<StepRef, ResearchStep> stepMap,
        StepRef current,
        Set<StepRef> visited
    ) {
        if (visited.contains(current)) {
            return List.of(current);
        }

        var step = stepMap.get(current);
        if (step == null) {
            throw new IllegalArgumentException("Dependency outside of cyclic steps: " + current);
        }

        visited.add(current);

        for (var dep : step.dependencies()) {
            var cycle = findCycleByTracing(stepMap, dep, visited);
            if (!cycle.isEmpty()) {
                var extended = new ArrayList<>(cycle);
                extended.add(current);
                return extended;
            }
        }

        visited.remove(current);
        return List.of();
    }
}