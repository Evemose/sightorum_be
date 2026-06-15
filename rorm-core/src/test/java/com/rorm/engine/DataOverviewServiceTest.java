package com.rorm.engine;

import com.rorm.metamodel.AttributeLocation;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.IdDescriptor;
import com.rorm.metamodel.PluralReferenceAttribute;
import com.rorm.metamodel.ReferenceAttribute;
import com.rorm.metamodel.Root;
import com.rorm.metamodel.SingularReferenceAttribute;
import com.rorm.query.Expression.Aggregation;
import com.rorm.query.Path;
import com.rorm.query.Query;
import com.rorm.query.SelectedExpression;
import com.rorm.query.StandardAggregation;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.TestHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DataOverviewService query building")
class DataOverviewServiceTest {

    private final BasicAttribute age =
        new BasicAttribute("age", new AttributeLocation("people", "age"), new DataType.NumericType(10, 0));
    private final BasicAttribute createdAt =
        new BasicAttribute("createdAt", new AttributeLocation("people", "created_at"), new DataType.DateTimeType());
    private final BasicAttribute active =
        new BasicAttribute("active", new AttributeLocation("people", "active"), new DataType.BooleanType());
    private final BasicAttribute status =
        new BasicAttribute("status", new AttributeLocation("people", "status"), new DataType.StringType());
    private final SingularReferenceAttribute company =
        new SingularReferenceAttribute("company", new Root("companies", List.of(), IdDescriptor.longId("companies")),
            new ReferenceAttribute.SameTableColumn("company_id"));
    private final PluralReferenceAttribute orders =
        new PluralReferenceAttribute("orders", new Root("orders", List.of(), IdDescriptor.longId("orders")),
            new ReferenceAttribute.SameTableColumn("person_id"));
    private final Root people = new Root(
        "people", List.of(age, createdAt, active, status, company, orders), IdDescriptor.longId("people"));

    private final DataOverviewService service =
        new DataOverviewService(new ExpressionTypeResolver(TestHandlerRegistry.createWithAllBuiltIns()));

    @Test
    @DisplayName("dispatches each type category to the matching query shape")
    void dispatchesByCategory() {
        assertThat(aliasesOf(service.buildAnalysisQuery(people, new Path(age), TypeCategory.NUMERIC)))
            .contains("min", "max", "avg", "sum", "stddev", "variance");
        assertThat(aliasesOf(service.buildAnalysisQuery(people, new Path(createdAt), TypeCategory.TEMPORAL)))
            .contains("earliest", "latest");
        assertThat(aliasesOf(service.buildAnalysisQuery(people, new Path(active), TypeCategory.BOOLEAN)))
            .contains("true_count", "false_count");
        assertThat(aliasesOf(service.buildAnalysisQuery(people, new Path(status), TypeCategory.CATEGORICAL)))
            .contains("value", "count");
    }

    @Test
    @DisplayName("reference category resolves singular vs plural references to different shapes")
    void dispatchesReferenceByCardinality() {
        assertThat(aliasesOf(service.buildAnalysisQuery(people, new Path(company), TypeCategory.REFERENCE)))
            .contains("linked_count", "unlinked_count");
        assertThat(aliasesOf(service.buildAnalysisQuery(people, new Path(orders), TypeCategory.REFERENCE)))
            .contains("parent_id", "child_count");
    }

    @Test
    @DisplayName("collection and unknown categories fall back to a distinct count")
    void fallsBackToDistinctCount() {
        var query = service.buildAnalysisQuery(people, new Path(status), TypeCategory.UNKNOWN);
        assertThat(query.selector()).isInstanceOf(SingleExprSelector.class);
        assertThat(((SingleExprSelector) query.selector()).alias()).isEqualTo("distinct_count");
        assertThat(((SingleExprSelector) query.selector()).expression()).isInstanceOf(com.rorm.query.Expression.Aggregation.class);
    }

    @Test
    @DisplayName("numeric stats query exposes the full statistic set wired to the matching aggregations")
    void numericStatsQuery() {
        var query = service.buildNumericStatsQuery(people, new Path(age));
        assertThat(aliasesOf(query)).containsExactlyInAnyOrder(
            "total_count", "non_null_count", "null_count", "min", "max", "avg", "sum", "stddev", "variance");
        assertThat(aggregationBehind(query, "min")).isEqualTo(StandardAggregation.MIN.identifier());
        assertThat(aggregationBehind(query, "max")).isEqualTo(StandardAggregation.MAX.identifier());
        assertThat(aggregationBehind(query, "avg")).isEqualTo(StandardAggregation.AVG.identifier());
        assertThat(aggregationBehind(query, "sum")).isEqualTo(StandardAggregation.SUM.identifier());
    }

    @Test
    @DisplayName("categorical frequency query groups, orders by descending count and filters nulls when excluded")
    void categoricalFrequencyQuery() {
        var query = service.buildCategoricalFrequencyQuery(people, new Path(status), 20, false);
        assertThat(query.groupBy()).isNotNull();
        assertThat(query.orderBy()).singleElement().satisfies(o -> assertThat(o.ascending()).isFalse());
        assertThat(query.where()).isNotNull();
        assertThat(query.limit()).isEqualTo(20L);
    }

    @Test
    @DisplayName("categorical frequency query keeps nulls and omits the limit when requested")
    void categoricalFrequencyQueryWithNulls() {
        var query = service.buildCategoricalFrequencyQuery(people, new Path(status), null, true);
        assertThat(query.where()).isNull();
        assertThat(query.limit()).isNull();
    }

    @Test
    @DisplayName("numeric histogram query buckets, filters nulls and orders by bucket")
    void numericHistogramQuery() {
        var query = service.buildNumericHistogramQuery(people, new Path(age), 10);
        assertThat(aliasesOf(query)).containsExactlyInAnyOrder("bucket", "count", "bucket_min", "bucket_max");
        assertThat(query.where()).isNotNull();
        assertThat(query.groupBy()).isNotNull();
        assertThat(query.orderBy()).singleElement().satisfies(o -> assertThat(o.ascending()).isTrue());
    }

    @Test
    @DisplayName("percentiles, null count, temporal distribution and plural summary queries build")
    void otherNumericAndTemporalQueries() {
        assertThat(aliasesOf(service.buildPercentilesQuery(people, new Path(age))))
            .containsExactlyInAnyOrder("p25", "p50_median", "p75", "p90", "p95", "p99");
        assertThat(((SingleExprSelector) service.buildNullCountQuery(people, new Path(age)).selector()).alias())
            .isEqualTo("null_count");
        assertThat(aliasesOf(service.buildTemporalDistributionQuery(
            people, new Path(createdAt), DataOverviewService.TemporalGranularity.MONTH))).contains("period", "count");
        assertThat(aliasesOf(service.buildPluralReferenceSummaryQuery(people, new Path(orders))))
            .contains("avg_children", "min_children", "max_children");
    }

    @Test
    @DisplayName("rejects expressions whose type does not match the requested analysis")
    void rejectsTypeMismatches() {
        assertThatThrownBy(() -> service.buildNumericStatsQuery(people, new Path(status)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("numeric");
        assertThatThrownBy(() -> service.buildTemporalRangeQuery(people, new Path(age)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("temporal");
        assertThatThrownBy(() -> service.buildBooleanDistributionQuery(people, new Path(age)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("boolean");
    }

    @Test
    @DisplayName("rejects malformed reference and histogram inputs")
    void rejectsMalformedReferenceAndHistogram() {
        assertThatThrownBy(() -> service.buildSingularReferenceCountQuery(people, new Path(age)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ReferenceAttribute");
        assertThatThrownBy(() -> service.buildPluralReferenceStatsQuery(people, new Path(company)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PluralReferenceAttribute");
        assertThatThrownBy(() -> service.buildNumericHistogramQuery(people, new Path(age), 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Bin count");
    }

    private static Set<String> aliasesOf(Query query) {
        return ((MultiExprSelector) query.selector()).expressions().stream()
            .map(SelectedExpression::alias)
            .collect(Collectors.toSet());
    }

    private static String aggregationBehind(Query query, String alias) {
        var expression = ((MultiExprSelector) query.selector()).expressions().stream()
            .filter(selected -> alias.equals(selected.alias()))
            .map(SelectedExpression::expression)
            .findFirst()
            .orElseThrow();
        return ((Aggregation) expression).functionName();
    }
}
