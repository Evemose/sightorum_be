package com.rorm.ai.tools;

import com.rorm.ai.RormToolContext;
import com.rorm.dto.dense.DenseExpressionDto;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.rorm.ai.tools.DescriptiveQueryBuilder.*;
import static com.rorm.ai.tools.VerificationQueryExecutor.longVal;
import static com.rorm.ai.tools.VerificationQueryExecutor.numVal;

@Component
@RequiredArgsConstructor
class AxisFanout {

    private final VerificationQueryExecutor executor;

    List<AxisResult> fanout(String rootName,
                            @Nullable DenseExpressionDto filter,
                            DenseExpressionDto measure,
                            String kind,
                            @Nullable DenseExpressionDto denominator,
                            List<DenseExpressionDto> axes,
                            RormToolContext ctx) {
        var results = new ArrayList<AxisResult>();
        for (var axis : axes) {
            var query = groupedQuery(rootName, axis, filter,
                measureSelector(measure, kind, denominator, "value"),
                countStar("n"));
            var rows = executor.execute(query, ctx);
            var segments = new ArrayList<Segment>();
            for (var row : rows) {
                segments.add(new Segment(
                    String.valueOf(row.get("category")),
                    numVal(row, "value"),
                    longVal(row, "n")
                ));
            }
            results.add(new AxisResult(axis, segments));
        }
        return results;
    }

    List<BucketedAxisResult> fanoutByBucket(String rootName,
                                            @Nullable DenseExpressionDto filter,
                                            DenseExpressionDto timeExpression,
                                            String grain,
                                            DenseExpressionDto measure,
                                            String kind,
                                            @Nullable DenseExpressionDto denominator,
                                            List<DenseExpressionDto> axes,
                                            RormToolContext ctx) {
        var results = new ArrayList<BucketedAxisResult>();
        var bucketExpr = dateTruncBucket(grain, timeExpression);
        for (var axis : axes) {
            var query = bucketedByAxisQuery(rootName, bucketExpr, axis, filter,
                measureSelector(measure, kind, denominator, "value"));
            var rows = executor.execute(query, ctx);
            var grouped = new LinkedHashMap<String, List<Segment>>();
            for (var row : rows) {
                var bucketKey = String.valueOf(row.get("bucket"));
                var segmentKey = String.valueOf(row.get("segment"));
                var value = numVal(row, "value");
                grouped.computeIfAbsent(bucketKey, _ -> new ArrayList<>())
                    .add(new Segment(segmentKey, value, 0L));
            }
            results.add(new BucketedAxisResult(axis, grouped));
        }
        return results;
    }

    public record Segment(String key, double value, long n) {}

    public record AxisResult(DenseExpressionDto axis, List<Segment> segments) {}

    public record BucketedAxisResult(DenseExpressionDto axis,
                                     Map<String, List<Segment>> segmentsByBucket) {}
}
