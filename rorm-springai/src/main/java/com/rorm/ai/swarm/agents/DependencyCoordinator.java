package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.dto.StepRef;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Coordinates step dependencies using latches
 */
public class DependencyCoordinator {
    private final Map<StepRef, CountDownLatch> latches = new ConcurrentHashMap<>();

    void awaitDependencies(StepRef step, List<StepRef> dependencies) throws InterruptedException {
        if (dependencies.isEmpty()) {
            return;
        }

        latches.computeIfAbsent(step, _ ->
            new CountDownLatch(dependencies.size())
        ).await();
    }

    void markComplete(StepRef step, List<StepRef> dependents) {
        dependents.forEach(dependent ->
            latches.computeIfAbsent(dependent, _ ->
                new CountDownLatch(1)
            ).countDown()
        );
    }
}