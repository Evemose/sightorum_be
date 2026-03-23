package com.rorm.ai.tools;

import java.util.*;

import static com.rorm.ai.tools.VerificationQueryExecutor.longVal;
import static com.rorm.ai.tools.VerificationQueryExecutor.numVal;

final class StatisticalPrimitives {

    private StatisticalPrimitives() {
    }

    static List<GroupGradient> parseGroupGradients(List<Map<String, Object>> rows) {
        return rows.stream()
            .map(row -> new GroupGradient(
                String.valueOf(row.get("category")),
                longVal(row, "n"),
                numVal(row, "gradient"),
                numVal(row, "slope"),
                numVal(row, "avg_outcome"),
                numVal(row, "avg_feature")))
            .toList();
    }

    static double weightedAverage(List<Map<String, Object>> rows, String valueKey, long minN) {
        var totalWeight = 0L;
        var weightedSum = 0.0;
        for (var row : rows) {
            var value = numVal(row, valueKey);
            var n = longVal(row, "n");
            if (n >= minN && Double.isFinite(value)) {
                weightedSum += value * n;
                totalWeight += n;
            }
        }
        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    }

    static CrossTab buildCrossTab(List<Map<String, Object>> rows) {
        var totalN = rows.stream().mapToLong(r -> longVal(r, "n")).sum();
        var cells = new ArrayList<Cell>();
        var marginA = new HashMap<String, Long>();
        var marginB = new HashMap<String, Long>();

        for (var row : rows) {
            var a = String.valueOf(row.get("group1"));
            var b = String.valueOf(row.get("group2"));
            var n = longVal(row, "n");
            cells.add(new Cell(a, b, n, numVal(row, "outcome_rate"),
                totalN > 0 ? n * 100.0 / totalN : 0.0));
            marginA.merge(a, n, Long::sum);
            marginB.merge(b, n, Long::sum);
        }

        return new CrossTab(cells, totalN, marginA, marginB, detectOverlap(cells, marginA, marginB));
    }

    private static Map<String, Object> detectOverlap(List<Cell> cells,
                                                     Map<String, Long> marginA,
                                                     Map<String, Long> marginB) {
        var nonEmpty = cells.stream().filter(c -> c.n() > 0).count();
        var maxCells = (long) marginA.size() * marginB.size();
        var fillRate = maxCells > 0 ? nonEmpty / (double) maxCells : 0;

        var concentrationPerA = marginA.keySet().stream()
            .map(a -> {
                var totalForA = marginA.get(a);
                var maxShareInB = cells.stream()
                    .filter(c -> c.a().equals(a) && c.n() > 0)
                    .mapToDouble(c -> (double) c.n() / totalForA)
                    .max().orElse(0);
                var dominantB = cells.stream()
                    .filter(c -> c.a().equals(a) && c.n() > 0)
                    .max(Comparator.comparingLong(Cell::n))
                    .map(Cell::b).orElse("");
                return Map.<String, Object>of(
                    "a_value", a, "dominant_b", dominantB,
                    "concentration", maxShareInB, "n", totalForA);
            })
            .toList();

        var nearSubsetCount = (int) concentrationPerA.stream()
            .filter(m -> ((Number) m.get("concentration")).doubleValue() > 0.9)
            .count();

        return Map.of(
            "fill_rate", fillRate,
            "concentration_per_a", concentrationPerA,
            "near_subset_count", nearSubsetCount,
            "all_concentrated", nearSubsetCount == marginA.size());
    }

    static Inflection detectInflection(List<Map<String, Object>> bins) {
        var maxDelta = 0.0;
        var inflectionPoint = 0.0;

        for (var i = 1; i < bins.size(); i++) {
            var prevRate = numVal(bins.get(i - 1), "outcome_rate");
            var currRate = numVal(bins.get(i), "outcome_rate");
            var delta = Math.abs(currRate - prevRate);
            if (delta > maxDelta) {
                maxDelta = delta;
                var prevMid = numVal(bins.get(i - 1), "bin_midpoint");
                var currMid = numVal(bins.get(i), "bin_midpoint");
                inflectionPoint = (prevMid + currMid) / 2;
            }
        }

        return new Inflection(inflectionPoint, maxDelta);
    }

    static List<LevelDistribution> computeLevelDistributions(List<Map<String, Object>> rows) {
        var byLevel = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var row : rows) {
            byLevel.computeIfAbsent(String.valueOf(row.get("group1")), _ -> new ArrayList<>()).add(row);
        }

        var levels = new ArrayList<LevelDistribution>();
        for (var entry : byLevel.entrySet()) {
            var levelRows = entry.getValue();
            var totalN = levelRows.stream().mapToLong(r -> longVal(r, "n")).sum();

            var perGroup = new ArrayList<Map<String, Object>>();
            var hhi = 0.0;
            for (var row : levelRows) {
                var n = longVal(row, "n");
                var share = totalN > 0 ? (double) n / totalN : 0.0;
                hhi += share * share;
                perGroup.add(Map.of(
                    "group", String.valueOf(row.get("group2")),
                    "n", n,
                    "pct_of_level", share * 100,
                    "outcome_rate", numVal(row, "outcome_rate")));
            }

            levels.add(new LevelDistribution(entry.getKey(), totalN, perGroup, hhi, perGroup.size()));
        }
        return levels;
    }

    record GroupGradient(String category, long n, double gradient, double slope,
                         double avgOutcome, double avgFeature) {

        boolean significant(double gradientThreshold, long minN) {
            return Math.abs(gradient) > gradientThreshold && n >= minN;
        }

        Map<String, Object> toMap() {
            return Map.of("category", category, "n", n, "gradient", gradient,
                "slope", slope, "avg_outcome", avgOutcome, "avg_feature", avgFeature);
        }
    }

    record Cell(String a, String b, long n, double outcomeRate, double pctOfTotal) {
        Map<String, Object> toMap() {
            return Map.of("a", a, "b", b, "n", n, "outcome_rate", outcomeRate, "pct_of_total", pctOfTotal);
        }
    }

    record CrossTab(List<Cell> cells, long totalN, Map<String, Long> marginA,
                    Map<String, Long> marginB, Map<String, Object> overlap) {}

    record Inflection(double point, double maxDelta) {}

    record LevelDistribution(String level, long totalN, List<Map<String, Object>> perGroup,
                             double hhi, int nGroups) {
        Map<String, Object> toMap() {
            return Map.of("level", level, "total_n", totalN, "per_group", perGroup,
                "concentration_hhi", hhi, "n_groups", nGroups);
        }
    }
}
