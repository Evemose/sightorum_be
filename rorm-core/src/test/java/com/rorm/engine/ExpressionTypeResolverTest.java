package com.rorm.engine;

import com.rorm.engine.ExpressionTypeResolver.ResolvedType;
import com.rorm.metamodel.AliasedRoot;
import com.rorm.metamodel.AttributeLocation;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.CollectionAttribute;
import com.rorm.metamodel.CompositeAttribute;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.IdDescriptor;
import com.rorm.metamodel.PluralReferenceAttribute;
import com.rorm.metamodel.ReferenceAttribute;
import com.rorm.metamodel.Root;
import com.rorm.metamodel.SingularReferenceAttribute;
import com.rorm.query.CaseExpression;
import com.rorm.query.Expression.Aggregation;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Expression.FunctionCall;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Expression.QuantifiedComparison;
import com.rorm.query.Expression.TernaryExpression;
import com.rorm.query.Expression.UnaryExpression;
import com.rorm.query.Expression.WindowFunction;
import com.rorm.query.Path;
import com.rorm.query.StandardOperator;
import com.rorm.query.StandardWindowFunction;
import com.rorm.query.Subquery;
import com.rorm.query.Query;
import com.rorm.query.SelectedExpression;
import com.rorm.query.WindowSpec;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.TestHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExpressionTypeResolver")
class ExpressionTypeResolverTest {

    private final BasicAttribute age =
        new BasicAttribute("age", new AttributeLocation("people", "age"), new DataType.NumericType(10, 0));
    private final CompositeAttribute address = new CompositeAttribute("address", Set.of(
        new BasicAttribute("zip", new AttributeLocation("people", "zip"), new DataType.StringType())));
    private final SingularReferenceAttribute company =
        new SingularReferenceAttribute("company", new Root("companies", List.of(), IdDescriptor.longId("companies")),
            new ReferenceAttribute.SameTableColumn("company_id"));
    private final PluralReferenceAttribute orders =
        new PluralReferenceAttribute("orders", new Root("orders", List.of(), IdDescriptor.stringId("orders")),
            new ReferenceAttribute.SameTableColumn("person_id"));
    private final CollectionAttribute tags = new CollectionAttribute("tags", "people",
        new CollectionAttribute.BasicElement(new AttributeLocation("people", "tags"), new DataType.StringType()));
    private final Root people =
        new Root("people", List.of(age, address, company, orders, tags), IdDescriptor.longId("people"));

    private final ExpressionTypeResolver resolver =
        new ExpressionTypeResolver(TestHandlerRegistry.createWithAllBuiltIns());

    static Stream<Arguments> literals() {
        return Stream.of(
            Arguments.of("text", DataType.StringType.class),
            Arguments.of(true, DataType.BooleanType.class),
            Arguments.of(42, DataType.NumericType.class),
            Arguments.of(3.14, DataType.NumericType.class),
            Arguments.of(new BigDecimal("1.50"), DataType.NumericType.class),
            Arguments.of(LocalDate.of(2026, 1, 1), DataType.DateType.class),
            Arguments.of(LocalTime.NOON, DataType.TimeType.class),
            Arguments.of(Instant.EPOCH, DataType.DateTimeType.class),
            Arguments.of(DayOfWeek.MONDAY, DataType.DayOfWeekType.class),
            Arguments.of(List.of(1, 2, 3), DataType.ListType.class)
        );
    }

    @ParameterizedTest
    @MethodSource("literals")
    @DisplayName("infers the data type of literal values")
    void infersLiteralTypes(Object value, Class<? extends DataType> expected) {
        assertThat(resolver.resolveWithRoot(new Literal(value), people)).isInstanceOf(expected);
    }

    @Test
    @DisplayName("a NULL literal resolves to a null data type")
    void nullLiteralResolvesToNull() {
        assertThat(resolver.resolveWithRoot(new Literal(null), people)).isNull();
    }

    @Test
    @DisplayName("infers exact precision and scale for numeric literals")
    void infersNumericLiteralPrecisionAndScale() {
        assertThat(resolver.resolveWithRoot(new Literal(42), people))
            .isEqualTo(new DataType.NumericType(19, 0));
        assertThat(resolver.resolveWithRoot(new Literal(3.14), people))
            .isEqualTo(new DataType.NumericType(15, 6));
    }

    @Test
    @DisplayName("resolves a basic attribute path to its declared type")
    void resolvesBasicPath() {
        assertThat(resolver.resolveWithRoot(new Path(age), people)).isInstanceOf(DataType.NumericType.class);
    }

    @Test
    @DisplayName("resolves reference paths to reference metadata carrying the target id type")
    void resolvesReferencePaths() {
        assertThat(resolver.resolveType(new Path(company), people))
            .isInstanceOfSatisfying(ResolvedType.SingularReference.class,
                ref -> assertThat(ref.idType()).isInstanceOf(DataType.NumericType.class));
        assertThat(resolver.resolveType(new Path(orders), people))
            .isInstanceOfSatisfying(ResolvedType.PluralReference.class,
                ref -> assertThat(ref.idType()).isInstanceOf(DataType.StringType.class));
    }

    @Test
    @DisplayName("a composite path cannot be projected and fails when forced to a scalar type")
    void compositePathIsNotScalar() {
        assertThat(resolver.resolveType(new Path(address), people)).isInstanceOf(ResolvedType.Composite.class);
        assertThatThrownBy(() -> resolver.resolveWithRoot(new Path(address), people))
            .isInstanceOf(TypeResolutionException.class);
    }

    @Test
    @DisplayName("categorizes by data type, and reference paths as REFERENCE")
    void categorizesExpressions() {
        assertThat(resolver.categorize(new Path(age), people)).isEqualTo(TypeCategory.NUMERIC);
        assertThat(resolver.categorize(new Path(company), people)).isEqualTo(TypeCategory.REFERENCE);
    }

    @Test
    @DisplayName("exposes reference cardinality predicates over paths")
    void referencePredicates() {
        assertThat(resolver.isReferenceType(new Path(company))).isTrue();
        assertThat(resolver.isSingularReference(new Path(company))).isTrue();
        assertThat(resolver.isPluralReference(new Path(orders))).isTrue();
        assertThat(resolver.isReferenceType(new Path(age))).isFalse();
    }

    @Test
    @DisplayName("resolves aggregations through their handler")
    void resolvesAggregation() {
        var count = new Aggregation("COUNT", List.of(new Literal("*")), false);
        assertThat(resolver.resolveWithRoot(count, people)).isInstanceOf(DataType.NumericType.class);
    }

    @Test
    @DisplayName("resolves function calls and binary/unary operators through their handlers")
    void resolvesThroughHandlers() {
        assertThat(resolver.resolveWithRoot(new FunctionCall("ABS", List.of(new Path(age))), people))
            .isInstanceOf(DataType.NumericType.class);
        assertThat(resolver.resolveWithRoot(
            BinaryExpression.of(new Path(age), StandardOperator.Binary.ADD, new Literal(1)), people))
            .isInstanceOf(DataType.NumericType.class);
        assertThat(resolver.resolveWithRoot(
            new UnaryExpression(StandardOperator.Unary.IS_NULL.identifier(), new Path(age)), people))
            .isInstanceOf(DataType.BooleanType.class);
    }

    @Test
    @DisplayName("types a scalar subquery by its projection but rejects a multi-column subquery")
    void resolvesSubqueries() {
        var scalar = new Subquery(Query.builder().from(AliasedRoot.of(people))
            .selector(new SingleExprSelector(new Path(age), false, "a")).build());
        assertThat(resolver.resolveType(scalar, people)).isInstanceOf(ResolvedType.BasicType.class);

        var multi = new Subquery(Query.builder().from(AliasedRoot.of(people))
            .selector(new MultiExprSelector(Set.of(new SelectedExpression(new Path(age), "a")), false)).build());
        assertThat(resolver.resolveType(multi, people)).isInstanceOf(ResolvedType.Composite.class);
    }

    @Test
    @DisplayName("types a CASE expression by its first THEN result")
    void resolvesCaseExpression() {
        var caseExpr = new CaseExpression(
            List.of(new CaseExpression.WhenClause(new Literal(true), new Literal(42))), null);
        assertThat(resolver.resolveWithRoot(caseExpr, people)).isInstanceOf(DataType.NumericType.class);
    }

    @Test
    @DisplayName("types BETWEEN, quantified comparison and window functions via their handlers")
    void resolvesTernaryQuantifiedAndWindow() {
        assertThat(resolver.resolveWithRoot(
            TernaryExpression.between(new Path(age), new Literal(1), new Literal(100)), people))
            .isInstanceOf(DataType.BooleanType.class);
        var subquery = new Subquery(Query.builder().from(AliasedRoot.of(people))
            .selector(new SingleExprSelector(new Path(age), false, "a")).build());
        assertThat(resolver.resolveWithRoot(
            QuantifiedComparison.any(new Path(age), StandardOperator.Binary.EQUALS, subquery), people))
            .isInstanceOf(DataType.BooleanType.class);
        assertThat(resolver.resolveWithRoot(
            WindowFunction.of(StandardWindowFunction.ROW_NUMBER, new WindowSpec(null, null)), people))
            .isInstanceOf(DataType.NumericType.class);
    }

    @Test
    @DisplayName("infers list, enum and empty-list literal types")
    void infersCollectionAndCategoricalLiterals() {
        assertThat(resolver.resolveWithRoot(new Path(tags), people)).isInstanceOf(DataType.ListType.class);
        assertThat(resolver.resolveWithRoot(new Literal(Month.JANUARY), people))
            .isInstanceOf(DataType.CategorcialType.class);
        assertThat(resolver.resolveWithRoot(new Literal(List.of()), people)).isInstanceOf(DataType.ListType.class);
    }

    @Test
    @DisplayName("a composite-element collection and an aliased root cannot be projected directly")
    void rejectsCompositeCollectionAndAliasedRoot() {
        var compositeCollection = new CollectionAttribute("aliases", "people",
            new CollectionAttribute.CompositeElement(Set.of(
                new BasicAttribute("alias", new AttributeLocation("people", "alias"), new DataType.StringType()))));
        assertThat(resolver.resolveType(new Path(compositeCollection), people)).isInstanceOf(ResolvedType.Composite.class);
        assertThat(resolver.resolveType(new Path(AliasedRoot.of(people)), people)).isInstanceOf(ResolvedType.Composite.class);
    }

    @Test
    @DisplayName("the no-arg resolve() handles basic types and rejects an unprojectable composite")
    void resolveHandlesBasicAndRejectsComposite() {
        assertThat(resolver.resolve(new Literal(42))).isInstanceOf(DataType.NumericType.class);
        assertThat(resolver.resolve(new Path(age))).isInstanceOf(DataType.NumericType.class);
        assertThatThrownBy(() -> resolver.resolve(new Path(address))).isInstanceOf(TypeResolutionException.class);
    }
}
