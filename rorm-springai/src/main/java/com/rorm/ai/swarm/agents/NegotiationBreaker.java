package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.NegotiationFinishReason;

import java.util.List;
import java.util.Optional;

class NegotiationBreaker {

    static Optional<NegotiationFinishReason> shouldFinish(List<Double> scores, int iteration) {
        var hardLimit = 6;
        var softLimit = 4;

        if (iteration >= hardLimit) {
            return Optional.of(NegotiationFinishReason.MAX_ITERATIONS_REACHED);
        }

        var threshold = 8.5 - approachAsymptoteFromAbove(iteration, 1.5, 0.5);
        if (scores.getLast() >= threshold && iteration < softLimit) {
            return Optional.of(NegotiationFinishReason.APPROVED);
        }

        if (recentVsOverallAvg(scores, iteration) < 0.3) {
            return Optional.of(NegotiationFinishReason.INSIGNIFICANT_IMPROVEMENT);
        }

        return Optional.empty();
    }

    private static double approachAsymptoteFromAbove(int iteration, double asymptote, double rate) {
        return asymptote * (1 + Math.exp(-rate * iteration));
    }

    private static double recentVsOverallAvg(List<Double> scores, int iteration) {
        if (iteration < 3) {
            return 1.0;
        }

        var recentAvg = (scores.get(iteration - 2) + scores.get(iteration - 1)) / 2.0;
        var overallAvg = scores.stream().limit(iteration + 1).mapToDouble(Double::doubleValue).average().orElse(0);

        return recentAvg - overallAvg;
    }

}
