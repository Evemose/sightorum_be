package com.rorm.ai.tools;

import com.rorm.dto.dense.DenseExpressionDto;
import com.rorm.dto.dense.DenseQueryDto;
import com.rorm.dto.dense.DenseQueryDto.SelectedExpressionDto;
import com.rorm.dto.dense.DenseSelectorDto;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class VerificationQueryBuilder {

    private VerificationQueryBuilder() {
    }

    static DenseQueryDto query(String rootName, @Nullable DenseExpressionDto filter,
                               SelectedExpressionDto... selections) {
        return new DenseQueryDto(rootName, "t", DenseSelectorDto.multi(Set.of(selections), false),
            null, filter, null, null, null, null, null);
    }

    static DenseQueryDto groupedQuery(String rootName, DenseExpressionDto groupByExpr,
                                      @Nullable DenseExpressionDto filter,
                                      SelectedExpressionDto... aggregations) {
        var allSelections = new LinkedHashSet<SelectedExpressionDto>();
        allSelections.add(new SelectedExpressionDto(groupByExpr, "category"));
        Collections.addAll(allSelections, aggregations);

        var groupBy = new DenseQueryDto.GroupByDto(List.of(groupByExpr));
        return new DenseQueryDto(rootName, "t", DenseSelectorDto.multi(allSelections, false),
            null, filter, groupBy, null, null, null, null);
    }

    static DenseQueryDto doubleGroupedQuery(String rootName,
                                            DenseExpressionDto g1, DenseExpressionDto g2,
                                            @Nullable DenseExpressionDto filter,
                                            SelectedExpressionDto... aggregations) {
        var allSelections = new LinkedHashSet<SelectedExpressionDto>();
        allSelections.add(new SelectedExpressionDto(g1, "group1"));
        allSelections.add(new SelectedExpressionDto(g2, "group2"));
        Collections.addAll(allSelections, aggregations);

        var groupBy = new DenseQueryDto.GroupByDto(List.of(g1, g2));
        return new DenseQueryDto(rootName, "t", DenseSelectorDto.multi(allSelections, false),
            null, filter, groupBy, null, null, null, null);
    }

    static SelectedExpressionDto corr(DenseExpressionDto y, DenseExpressionDto x, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("CORR", List.of(y, x), false), alias);
    }

    static SelectedExpressionDto regrSlope(DenseExpressionDto y, DenseExpressionDto x, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("REGR_SLOPE", List.of(y, x), false), alias);
    }

    static SelectedExpressionDto avg(DenseExpressionDto expr, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("AVG", List.of(expr), false), alias);
    }

    static SelectedExpressionDto stddevPop(DenseExpressionDto expr, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("STDDEV_POP", List.of(expr), false), alias);
    }

    static SelectedExpressionDto count(String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("COUNT", List.of(), false), alias);
    }
}
