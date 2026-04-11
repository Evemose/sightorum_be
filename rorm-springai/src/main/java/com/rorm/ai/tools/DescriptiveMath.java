package com.rorm.ai.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class DescriptiveMath {

    private DescriptiveMath() {
    }

    static LinearFit linearRegression(double[] series) {
        var n = series.length;
        if (n < 2) {
            return new LinearFit(0.0, n == 1 ? series[0] : 0.0, 0.0);
        }
        var meanX = (n - 1) / 2.0;
        var meanY = 0.0;
        for (var v : series) {
            meanY += v;
        }
        meanY /= n;

        var ssXY = 0.0;
        var ssXX = 0.0;
        var ssYY = 0.0;
        for (var i = 0; i < n; i++) {
            var dx = i - meanX;
            var dy = series[i] - meanY;
            ssXY += dx * dy;
            ssXX += dx * dx;
            ssYY += dy * dy;
        }
        if (ssXX == 0.0) {
            return new LinearFit(0.0, meanY, 0.0);
        }
        var slope = ssXY / ssXX;
        var intercept = meanY - slope * meanX;
        var rSq = ssYY == 0.0 ? 0.0 : (slope * ssXY) / ssYY;
        return new LinearFit(slope, intercept, rSq);
    }

    static CusumResult cusumChangePoint(double[] series) {
        var n = series.length;
        if (n < 4) {
            return new CusumResult(-1, 0.0, false);
        }
        var mean = 0.0;
        for (var v : series) {
            mean += v;
        }
        mean /= n;

        var std = 0.0;
        for (var v : series) {
            std += (v - mean) * (v - mean);
        }
        std = Math.sqrt(std / n);

        var cumulative = 0.0;
        var maxAbs = 0.0;
        var maxIndex = 0;
        for (var i = 0; i < n; i++) {
            cumulative += series[i] - mean;
            if (Math.abs(cumulative) > maxAbs) {
                maxAbs = Math.abs(cumulative);
                maxIndex = i;
            }
        }
        var threshold = 2.5 * std * Math.sqrt(n);
        return new CusumResult(maxIndex, maxAbs, maxAbs > threshold && std > 0.0);
    }

    static double varianceLevelCorrelation(double[] series, int windowSize) {
        var n = series.length;
        if (n < windowSize * 2 || windowSize < 2) {
            return 0.0;
        }
        var rollingMeans = new ArrayList<Double>();
        var rollingVars = new ArrayList<Double>();
        for (var i = 0; i <= n - windowSize; i++) {
            var sum = 0.0;
            for (var j = i; j < i + windowSize; j++) {
                sum += series[j];
            }
            var mean = sum / windowSize;
            var varSum = 0.0;
            for (var j = i; j < i + windowSize; j++) {
                varSum += (series[j] - mean) * (series[j] - mean);
            }
            rollingMeans.add(mean);
            rollingVars.add(varSum / windowSize);
        }
        return pearson(rollingMeans, rollingVars);
    }

    private static double pearson(List<Double> xs, List<Double> ys) {
        var n = xs.size();
        if (n < 2) {
            return 0.0;
        }
        var mx = 0.0;
        var my = 0.0;
        for (var i = 0; i < n; i++) {
            mx += xs.get(i);
            my += ys.get(i);
        }
        mx /= n;
        my /= n;

        var cov = 0.0;
        var varX = 0.0;
        var varY = 0.0;
        for (var i = 0; i < n; i++) {
            var dx = xs.get(i) - mx;
            var dy = ys.get(i) - my;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        if (varX == 0.0 || varY == 0.0) {
            return 0.0;
        }
        return cov / Math.sqrt(varX * varY);
    }

    static double totalVariationDistance(Map<String, Long> left, Map<String, Long> right) {
        var leftTotal = left.values().stream().mapToLong(Long::longValue).sum();
        var rightTotal = right.values().stream().mapToLong(Long::longValue).sum();
        if (leftTotal == 0 || rightTotal == 0) {
            return 0.0;
        }
        var keys = new java.util.HashSet<String>();
        keys.addAll(left.keySet());
        keys.addAll(right.keySet());
        var sum = 0.0;
        for (var key : keys) {
            var p = left.getOrDefault(key, 0L) / (double) leftTotal;
            var q = right.getOrDefault(key, 0L) / (double) rightTotal;
            sum += Math.abs(p - q);
        }
        return sum / 2.0;
    }

    static Direction direction(double value, double threshold) {
        if (Math.abs(value) < threshold) {
            return Direction.FLAT;
        }
        return value > 0 ? Direction.UP : Direction.DOWN;
    }

    static MajorityResult directionMajority(List<Direction> directions, Direction reference) {
        var agree = 0;
        var disagree = 0;
        var flat = 0;
        for (var d : directions) {
            if (d == Direction.FLAT) {
                flat++;
            } else if (d == reference) {
                agree++;
            } else {
                disagree++;
            }
        }
        Direction majority;
        if (agree > disagree) {
            majority = reference;
        } else if (disagree > agree) {
            majority = reference == Direction.UP ? Direction.DOWN : Direction.UP;
        } else {
            majority = Direction.FLAT;
        }
        return new MajorityResult(majority, agree, disagree, flat);
    }

    static GapResult gapSpread(double[] sortedDesc, int k) {
        var n = sortedDesc.length;
        if (n <= k || k <= 0 || n < 3) {
            return new GapResult(0.0, 0.0, 0.0, false);
        }
        var gaps = new double[n - 1];
        for (var i = 0; i < n - 1; i++) {
            gaps[i] = sortedDesc[i] - sortedDesc[i + 1];
        }
        var sortedGaps = gaps.clone();
        java.util.Arrays.sort(sortedGaps);
        double median;
        if (sortedGaps.length % 2 == 1) {
            median = sortedGaps[sortedGaps.length / 2];
        } else {
            median = (sortedGaps[sortedGaps.length / 2 - 1] + sortedGaps[sortedGaps.length / 2]) / 2.0;
        }
        var boundary = gaps[k - 1];
        if (median == 0.0) {
            return new GapResult(boundary, 0.0, 0.0, false);
        }
        var ratio = boundary / median;
        return new GapResult(boundary, median, ratio, ratio < 0.3);
    }

    static HeterogeneityResult heterogeneity(List<Double> segmentValues, double aggregateValue) {
        if (segmentValues.size() < 2) {
            return new HeterogeneityResult(1.0, 0.0, false);
        }
        var finite = segmentValues.stream().filter(Double::isFinite).toList();
        if (finite.size() < 2) {
            return new HeterogeneityResult(1.0, 0.0, false);
        }
        var min = finite.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        var max = finite.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        var ratio = Math.abs(min) < 1e-12 ? (Math.abs(max) < 1e-12 ? 1.0 : Double.POSITIVE_INFINITY)
            : Math.abs(max / min);

        var deviations = finite.stream()
            .map(v -> Math.abs(v - aggregateValue))
            .sorted()
            .toList();
        double medianDev;
        if (deviations.isEmpty()) {
            medianDev = 0.0;
        } else if (deviations.size() % 2 == 1) {
            medianDev = deviations.get(deviations.size() / 2);
        } else {
            medianDev = (deviations.get(deviations.size() / 2 - 1) + deviations.get(deviations.size() / 2)) / 2.0;
        }
        var maxDev = deviations.isEmpty() ? 0.0 : deviations.getLast();
        var deviationFactor = medianDev == 0.0 ? 0.0 : maxDev / medianDev;
        var fired = ratio >= 3.0 || deviationFactor > 2.0;
        return new HeterogeneityResult(ratio, deviationFactor, fired);
    }

    static double coefficientOfVariation(List<Double> values) {
        if (values.size() < 2) {
            return 0.0;
        }
        var mean = 0.0;
        for (var v : values) {
            mean += v;
        }
        mean /= values.size();
        if (Math.abs(mean) < 1e-12) {
            return 0.0;
        }
        var variance = 0.0;
        for (var v : values) {
            variance += (v - mean) * (v - mean);
        }
        variance /= values.size();
        return Math.sqrt(variance) / Math.abs(mean);
    }

    static List<Integer> topKIndices(double[] values, int k, boolean descending) {
        var indices = new ArrayList<Integer>();
        for (var i = 0; i < values.length; i++) {
            indices.add(i);
        }
        indices.sort(descending
            ? Comparator.comparingDouble((Integer i) -> values[i]).reversed()
            : Comparator.comparingDouble(i -> values[i]));
        return indices.subList(0, Math.min(k, indices.size()));
    }

    enum Direction {UP, DOWN, FLAT}

    record LinearFit(double slope, double intercept, double rSquared) {}

    record CusumResult(int breakIndex, double maxDeviation, boolean significant) {}

    record MajorityResult(Direction majority, int agree, int disagree, int flat) {}

    record GapResult(double boundaryGap, double medianGap, double ratio, boolean gapIsWeak) {}

    record HeterogeneityResult(double maxMinRatio, double maxDeviationFactor, boolean fired) {}
}
