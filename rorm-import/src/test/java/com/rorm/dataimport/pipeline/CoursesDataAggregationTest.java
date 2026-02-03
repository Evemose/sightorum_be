package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.engine.QueryTransformer;
import com.rorm.metamodel.*;
import com.rorm.metamodel.ReferenceAttribute.SameTableColumn;
import com.rorm.query.Expression.Aggregation;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Expression.WindowFunction;
import com.rorm.query.*;
import com.rorm.query.Selector.MultiExprSelector;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Courses Data Integration - Aggregation & Window Function Tests")
class CoursesDataAggregationTest extends AbstractImportTest {

    @Autowired
    private QueryTransformer queryTransformer;
    @Autowired
    private org.jooq.DSLContext dslContext;
    private TestDataContext ctx;
    @Autowired
    private MetamodelConverter metamodelConverter;

    CoursesDataAggregationTest() {
        super(true);
    }

    @BeforeAll
    void setupData() throws Exception {
        super.setupTestSchema();
        ctx = setupTestData();
    }

    private TestDataContext setupTestData() throws Exception {
        var dataSources = loadAllDataSources();
        var overridesByRoot = createAllOverrides();
        var detectionResult = modelSpaceDetector.detect(dataSources, overridesByRoot, ",");
        var schema = getSchemaName();
        var request = new ImportRequest(schema, dataSources, detectionResult);
        var result = dataImportPipeline.importData(request);

        assertThat(result.totalRowsImported()).isEqualTo(8753);

        var modelSpace = metamodelConverter.convertToModelSpace(detectionResult);
        var roots = modelSpace.roots();
        var studentsRoot = findRoot(roots, "students");
        var coursesRoot = findRoot(roots, "courses");
        var enrollmentsRoot = findRoot(roots, "enrollments");
        var reviewsRoot = findRoot(roots, "reviews");

        dslContext.execute("SET search_path TO " + schema);

        return new TestDataContext(studentsRoot, coursesRoot, enrollmentsRoot, reviewsRoot);
    }

    private List<ImportDataSource> loadAllDataSources() throws IOException {
        var sources = new ArrayList<ImportDataSource>();
        sources.add(new CsvDataSource(loadResourceFile("data/cources/students.csv")));
        sources.add(new CsvDataSource(loadResourceFile("data/cources/courses.csv")));
        sources.add(new CsvDataSource(loadResourceFile("data/cources/enrollments.csv")));
        sources.add(new CsvDataSource(loadResourceFile("data/cources/attendance.csv")));
        sources.add(new CsvDataSource(loadResourceFile("data/cources/reviews.csv")));
        return sources;
    }

    private Map<String, List<SchemaOverride>> createAllOverrides() {
        var studentsOverrides = List.<SchemaOverride>of(
            new SchemaOverride.IdAttributeOverride("id", "student_id", null)
        );

        var coursesOverrides = List.<SchemaOverride>of(
            new SchemaOverride.IdAttributeOverride("id", "course_id", null)
        );

        var enrollmentsOverrides = List.<SchemaOverride>of(
            new SchemaOverride.IdAttributeOverride("id", "enrollment_id", null),
            new SchemaOverride.SingularReferenceOverride("studentId", "students"),
            new SchemaOverride.SingularReferenceOverride("courseId", "courses")
        );

        var attendanceOverrides = List.<SchemaOverride>of(
            new SchemaOverride.IdAttributeOverride("id", "attendance_id", null),
            new SchemaOverride.SingularReferenceOverride("enrollmentId", "enrollments")
        );

        var reviewsOverrides = List.<SchemaOverride>of(
            new SchemaOverride.IdAttributeOverride("id", "review_id", null),
            new SchemaOverride.SingularReferenceOverride("studentId", "students"),
            new SchemaOverride.SingularReferenceOverride("courseId", "courses")
        );

        return Map.of(
            "students", studentsOverrides,
            "courses", coursesOverrides,
            "enrollments", enrollmentsOverrides,
            "attendance", attendanceOverrides,
            "reviews", reviewsOverrides
        );
    }

    private Root findRoot(Set<Root> roots, String name) {
        return roots.stream()
            .filter(r -> r.primaryTableName().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Root not found: " + name));
    }

    private java.nio.file.Path loadResourceFile(String resourcePath) throws IOException {
        return new ClassPathResource(resourcePath).getFile().toPath();
    }

    @Test
    @DisplayName("GROUP BY: Count students by country")
    void testGroupByCountStudentsByCountry() {
        var studentCountry = findAttribute(ctx.studentsRoot, "country");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(studentCountry, null), "country"),
                new SelectedExpression(
                    new Aggregation("COUNT", List.of(new Literal("*")), false),
                    "student_count"
                )
            ), false))
            .groupBy(new GroupBy(new Path(studentCountry, null)))
            .orderBy(new OrderBy(
                new Aggregation("COUNT", List.of(new Literal("*")), false),
                false
            ))
            .limit(5L)
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result)
            .hasSize(5)
            .satisfies(records -> {
                // Yemen has the most students (8), followed by 4 countries with 7 students each
                assertThat(records.getFirst().get("country", String.class)).isEqualTo("Yemen");
                assertThat(records.getFirst().get("student_count", Number.class).longValue()).isEqualTo(8);
            })
            .extracting(r -> r.get("student_count", Number.class).longValue())
            .containsExactly(8L, 7L, 7L, 7L, 7L)
            .isSortedAccordingTo(Comparator.reverseOrder());

    }

    private Attribute findAttribute(Root root, String name) {
        return root.attributes().stream()
            .filter(BasicAttribute.class::isInstance)
            .map(BasicAttribute.class::cast)
            .filter(a -> a.location().column().equals(name))
            .findFirst()
            .map(Attribute.class::cast)
            .or(() -> root.attributes().stream()
                .filter(SingularReferenceAttribute.class::isInstance)
                .map(SingularReferenceAttribute.class::cast)
                .filter(sr ->
                    sr.mappingStrategy() instanceof SameTableColumn(var columnName) &&
                    columnName.equals(name)
                ).findFirst()
            ).orElseThrow(() -> new IllegalArgumentException(
                "Attribute not found: " + name + " in root " + root.primaryTableName()
            ));
    }

    @Test
    @DisplayName("GROUP BY with HAVING: Countries with more than 3 students")
    void testGroupByHavingCountryThreshold() {
        var studentCountry = findAttribute(ctx.studentsRoot, "country");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(studentCountry, null), "country"),
                new SelectedExpression(
                    new Aggregation("COUNT", List.of(new Literal("*")), false),
                    "student_count"
                )
            ), false))
            .groupBy(new GroupBy(new Path(studentCountry, null)))
            .having(new BinaryExpression(
                new Aggregation("COUNT", List.of(new Literal("*")), false),
                StandardOperator.Binary.GREATER_THAN.identifier(),
                new Literal(3)
            ))
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result)
            .hasSize(37) // Exactly 37 countries have more than 3 students
            .extracting(r -> r.get("student_count", Number.class).longValue())
            .allMatch(count -> count > 3)
            .contains(8L, 7L, 6L, 5L, 4L); // Contains countries with 4-8 students

    }

    @Test
    @DisplayName("Aggregations: AVG, MIN, MAX GPA by country")
    void testMultipleAggregationsByCountry() {
        var studentCountry = findAttribute(ctx.studentsRoot, "country");
        var studentGpa = findAttribute(ctx.studentsRoot, "prior_gpa");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(studentCountry, null), "country"),
                new SelectedExpression(
                    new Aggregation("AVG", List.of(new Path(studentGpa, null)), false),
                    "avg_gpa"
                ),
                new SelectedExpression(
                    new Aggregation("MIN", List.of(new Path(studentGpa, null)), false),
                    "min_gpa"
                ),
                new SelectedExpression(
                    new Aggregation("MAX", List.of(new Path(studentGpa, null)), false),
                    "max_gpa"
                ),
                new SelectedExpression(
                    new Aggregation("COUNT", List.of(new Literal("*")), false),
                    "student_count"
                )
            ), false))
            .groupBy(new GroupBy(new Path(studentCountry, null)))
            .having(new BinaryExpression(
                new Aggregation("COUNT", List.of(new Literal("*")), false),
                StandardOperator.Binary.GREATER_THAN_OR_EQUAL.identifier(),
                new Literal(5)
            ))
            .orderBy(new OrderBy(
                new Aggregation("AVG", List.of(new Path(studentGpa, null)), false),
                false
            ))
            .limit(10L)
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result)
            .hasSize(10)
            .first()
            .satisfies(topCountry -> {
                // Mexico has the highest average GPA among countries with >= 5 students
                assertThat(topCountry.get("country", String.class)).isEqualTo("Mexico");
                assertThat(topCountry.get("avg_gpa", Number.class).doubleValue()).isCloseTo(3.51, within(0.01));
                assertThat(topCountry.get("min_gpa", Number.class).doubleValue()).isCloseTo(2.97, within(0.01));
                assertThat(topCountry.get("max_gpa", Number.class).doubleValue()).isCloseTo(3.86, within(0.01));
                assertThat(topCountry.get("student_count", Number.class).longValue()).isEqualTo(7);
            });

        assertThat(result)
            .allSatisfy(record -> {
                var avgGpa = record.get("avg_gpa", Number.class).doubleValue();
                var minGpa = record.get("min_gpa", Number.class).doubleValue();
                var maxGpa = record.get("max_gpa", Number.class).doubleValue();
                var count = record.get("student_count", Number.class).longValue();

                assertThat(minGpa).isLessThanOrEqualTo(avgGpa);
                assertThat(avgGpa).isLessThanOrEqualTo(maxGpa);
                assertThat(count).isGreaterThanOrEqualTo(5);
            })
            .extracting(r -> r.get("avg_gpa", Number.class).doubleValue())
            .isSortedAccordingTo(Comparator.reverseOrder()); // Ordered by AVG GPA DESC

    }

    @Test
    @DisplayName("Window Function: ROW_NUMBER() for student ranking by GPA")
    void testWindowFunctionRowNumber() {
        var studentName = findAttribute(ctx.studentsRoot, "name");
        var studentGpa = findAttribute(ctx.studentsRoot, "prior_gpa");
        var studentCountry = findAttribute(ctx.studentsRoot, "country");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(studentName, null), "name"),
                new SelectedExpression(new Path(studentCountry, null), "country"),
                new SelectedExpression(new Path(studentGpa, null), "gpa"),
                new SelectedExpression(
                    new WindowFunction(
                        "ROW_NUMBER",
                        List.of(),
                        new WindowSpec(
                            List.of(new Path(studentCountry, null)),
                            List.of(new OrderBy(new Path(studentGpa, null), false))
                        )
                    ),
                    "rank_in_country"
                )
            ), false))
            .where(new BinaryExpression(
                new Path(studentCountry, null),
                StandardOperator.Binary.IN.identifier(),
                new Literal(List.of("Canada", "Mexico", "Brazil"))
            ))
            .orderBy(new OrderBy(new Path(studentCountry, null), true))
            .orderBy(new OrderBy(new Path(studentGpa, null), false))
            .limit(15L)
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result)
            .hasSizeGreaterThan(0)
            .extracting(r -> r.get("country", String.class))
            .containsAnyOf("Brazil", "Canada", "Mexico"); // 1 Brazilian, 2 Canadians, 7 Mexicans

        var grouped = result.stream().collect(Collectors.groupingBy(r -> r.get("country", String.class)));

        // Verify each partition starts with rank 1
        grouped.forEach((_, records) -> {
            var firstRank = records.getFirst().get("rank_in_country", Number.class).intValue();
            assertThat(firstRank).isEqualTo(1);

            // Verify ranking is sequential within partition
            var ranks = records.stream()
                .map(r -> r.get("rank_in_country", Number.class).intValue())
                .toList();
            assertThat(ranks).isSorted();
        });

    }

    @Test
    @DisplayName("Window Function: RANK() and DENSE_RANK()")
    void testWindowFunctionRankAndDenseRank() {
        var courseName = findAttribute(ctx.coursesRoot, "course_name");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.coursesRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(courseName, null), "course_name"),
                new SelectedExpression(
                    new WindowFunction(
                        "RANK",
                        List.of(),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(courseName, null), true))
                        )
                    ),
                    "rank"
                ),
                new SelectedExpression(
                    new WindowFunction(
                        "DENSE_RANK",
                        List.of(),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(courseName, null), true))
                        )
                    ),
                    "dense_rank"
                )
            ), false))
            .limit(10L)
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result)
            .hasSize(10)
            .first()
            .satisfies(first -> {
                // First course alphabetically is "Art 114"
                assertThat(first.get("course_name", String.class)).isEqualTo("Art 114");
                assertThat(first.get("rank", Number.class).intValue()).isEqualTo(1);
                assertThat(first.get("dense_rank", Number.class).intValue()).isEqualTo(1);
            });

        assertThat(result)
            .extracting(r -> r.get("course_name", String.class))
            .containsExactly("Art 114", "Art 149", "Art 151", "Art 205", "Art 248",
                "Art 287", "Art 374", "Art 406", "Art 431", "Art 433")
            .isSorted();

        assertThat(result)
            .allSatisfy(record -> {
                var rank = record.get("rank", Number.class).intValue();
                var denseRank = record.get("dense_rank", Number.class).intValue();

                assertThat(rank).isPositive();
                assertThat(denseRank).isPositive().isLessThanOrEqualTo(rank);
            });

    }

    @Test
    @DisplayName("Complex GROUP BY with multiple columns")
    void testGroupByMultipleColumns() {
        var studentCountry = findAttribute(ctx.studentsRoot, "country");
        var studentScholarship = findAttribute(ctx.studentsRoot, "has_scholarship");
        var studentGpa = findAttribute(ctx.studentsRoot, "prior_gpa");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(studentCountry, null), "country"),
                new SelectedExpression(new Path(studentScholarship, null), "has_scholarship"),
                new SelectedExpression(
                    new Aggregation("COUNT", List.of(new Literal("*")), false),
                    "student_count"
                ),
                new SelectedExpression(
                    new Aggregation("AVG", List.of(new Path(studentGpa, null)), false),
                    "avg_gpa"
                )
            ), false))
            .groupBy(new GroupBy(new Path(studentCountry, null)))
            .groupBy(new GroupBy(new Path(studentScholarship, null)))
            .having(new BinaryExpression(
                new BinaryExpression(
                    new Aggregation("COUNT", List.of(new Literal("*")), false),
                    StandardOperator.Binary.GREATER_THAN.identifier(),
                    new Literal(3)
                ),
                StandardOperator.Binary.AND.identifier(),
                new BinaryExpression(
                    new Aggregation("AVG", List.of(new Path(studentGpa, null)), false),
                    StandardOperator.Binary.GREATER_THAN.identifier(),
                    new Literal(2.5)
                )
            ))
            .orderBy(new OrderBy(new Path(studentCountry, null), true))
            .orderBy(new OrderBy(new Path(studentScholarship, null), false))
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result)
            .hasSize(16) // Exactly 16 groups match: count > 3 AND avg_gpa > 2.5
            .allSatisfy(record -> {
                var count = record.get("student_count", Number.class).longValue();
                var avgGpa = record.get("avg_gpa", Number.class).doubleValue();

                assertThat(count).isGreaterThan(3);
                assertThat(avgGpa).isGreaterThan(2.5);
            })
            .extracting(r -> r.get("country", String.class))
            .isSorted(); // Results ordered by country ASC, then scholarship DESC

    }

    @Test
    @SuppressWarnings("java:S5853")
    @DisplayName("Window Function: LAG and LEAD")
    void testWindowFunctionLagLead() {
        var courseName = findAttribute(ctx.coursesRoot, "course_name");
        var estimatedHours = findAttribute(ctx.coursesRoot, "estimated_hours");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.coursesRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(courseName, null), "course_name"),
                new SelectedExpression(new Path(estimatedHours, null), "estimated_hours"),
                new SelectedExpression(
                    new WindowFunction(
                        "LAG",
                        List.of(new Path(estimatedHours, null), new Literal(1)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(courseName, null), true))
                        )
                    ),
                    "previous_hours"
                ),
                new SelectedExpression(
                    new WindowFunction(
                        "LEAD",
                        List.of(new Path(estimatedHours, null), new Literal(1)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(courseName, null), true))
                        )
                    ),
                    "next_hours"
                )
            ), false))
            .orderBy(new OrderBy(new Path(courseName, null), true))
            .limit(20L)
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result)
            .hasSize(20)
            .first()
            .satisfies(firstRecord -> {
                // First course alphabetically is "Art 114"
                assertThat(firstRecord.get("course_name", String.class)).isEqualTo("Art 114");
                assertThat(firstRecord.get("estimated_hours", Number.class).intValue()).isEqualTo(75);
                assertThat(firstRecord.get("previous_hours")).isNull(); // No previous row
            });

        assertThat(result)
            .element(1)
            .satisfies(secondCourse -> {
                // Second course should have LAG value equal to first course's hours
                assertThat(secondCourse.get("course_name", String.class)).isEqualTo("Art 149");
                assertThat(secondCourse.get("previous_hours", Number.class).intValue()).isEqualTo(75); // Art 114's hours
                assertThat(secondCourse.get("estimated_hours", Number.class).intValue()).isEqualTo(45);
            });
    }

    @Test
    @DisplayName("Window Function: LAG and LEAD with 3 arguments (default values)")
    void testWindowFunctionLagLeadWithDefaultValues() {
        var courseName = findAttribute(ctx.coursesRoot, "course_name");
        var estimatedHours = findAttribute(ctx.coursesRoot, "estimated_hours");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.coursesRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(courseName, null), "course_name"),
                new SelectedExpression(new Path(estimatedHours, null), "estimated_hours"),
                new SelectedExpression(
                    new WindowFunction(
                        "LAG",
                        List.of(new Path(estimatedHours, null), new Literal(1), new Literal(-999)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(courseName, null), true))
                        )
                    ),
                    "lag_hours"
                ),
                new SelectedExpression(
                    new WindowFunction(
                        "LEAD",
                        List.of(new Path(estimatedHours, null), new Literal(1), new Literal(999)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(courseName, null), true))
                        )
                    ),
                    "lead_hours"
                )
            ), false))
            .orderBy(new OrderBy(new Path(courseName, null), true))
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result).hasSize(100);

        // First row: LAG should use default (-999) since there's no previous row
        assertThat(result.get(0).get("lag_hours", Number.class).intValue())
            .isEqualTo(-999);

        // Second row: LAG should return the first row's estimated_hours
        assertThat(result.get(1).get("lag_hours", Number.class).intValue())
            .isEqualTo(result.get(0).get("estimated_hours", Number.class).intValue());

        // Last row: LEAD should use default (999) since there's no next row
        assertThat(result.getLast().get("lead_hours", Number.class).intValue())
            .isEqualTo(999);

        // Second-to-last row (index 98): LEAD should return last row's estimated_hours
        assertThat(result.get(98).get("lead_hours", Number.class).intValue())
            .isEqualTo(result.getLast().get("estimated_hours", Number.class).intValue());
    }

    @Test
    @DisplayName("Window Function: FIRST_VALUE and LAST_VALUE")
    void testWindowFunctionFirstValueLastValue() {
        var studentCountry = findAttribute(ctx.studentsRoot, "country");
        var studentGpa = findAttribute(ctx.studentsRoot, "prior_gpa");
        var studentName = findAttribute(ctx.studentsRoot, "name");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(studentName, null), "name"),
                new SelectedExpression(new Path(studentCountry, null), "country"),
                new SelectedExpression(new Path(studentGpa, null), "gpa"),
                new SelectedExpression(
                    new WindowFunction(
                        "FIRST_VALUE",
                        List.of(new Path(studentName, null)),
                        new WindowSpec(
                            List.of(new Path(studentCountry, null)),
                            List.of(new OrderBy(new Path(studentGpa, null), false))
                        )
                    ),
                    "top_student"
                ),
                new SelectedExpression(
                    new WindowFunction(
                        "LAST_VALUE",
                        List.of(new Path(studentName, null)),
                        new WindowSpec(
                            List.of(new Path(studentCountry, null)),
                            List.of(new OrderBy(new Path(studentGpa, null), false))
                        )
                    ),
                    "bottom_student"
                )
            ), false))
            .where(new BinaryExpression(
                new Path(studentCountry, null),
                StandardOperator.Binary.IN.identifier(),
                new Literal(List.of("Canada", "Mexico"))
            ))
            .orderBy(new OrderBy(new Path(studentCountry, null), true))
            .orderBy(new OrderBy(new Path(studentGpa, null), false))
            .limit(20L)
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result).isNotEmpty();

        var grouped = result.stream()
            .collect(Collectors.groupingBy(r -> r.get("country", String.class)));

        assertThat(grouped)
            .extractingByKey("Canada")
            .asInstanceOf(InstanceOfAssertFactories.list(org.jooq.Record.class))
            .extracting(r -> r.get("top_student", String.class))
            .containsOnly("Patrick Thornton");

        assertThat(grouped)
            .extractingByKey("Mexico")
            .asInstanceOf(InstanceOfAssertFactories.list(org.jooq.Record.class))
            .extracting(r -> r.get("top_student", String.class))
            .containsOnly("Linda Robles"); // Mexico: Linda Robles has highest GPA (3.86)
    }

    @Test
    @DisplayName("Window Function: NTH_VALUE")
    void testWindowFunctionNthValue() {
        var courseName = findAttribute(ctx.coursesRoot, "course_name");
        var estimatedHours = findAttribute(ctx.coursesRoot, "estimated_hours");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.coursesRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(courseName, null), "course_name"),
                new SelectedExpression(new Path(estimatedHours, null), "estimated_hours"),
                new SelectedExpression(
                    new WindowFunction(
                        "NTH_VALUE",
                        List.of(new Path(estimatedHours, null), new Literal(3)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(estimatedHours, null), false))
                        )
                    ),
                    "third_highest_hours"
                )
            ), false))
            .orderBy(new OrderBy(new Path(estimatedHours, null), false))
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result)
            .hasSize(100)
            .extracting(r -> r.get("third_highest_hours", Number.class))
            .startsWith(new Number[]{null, null})
            .endsWith(
                IntStream.range(0, 98)
                    .mapToObj(_ -> result.get(2).get("third_highest_hours", Number.class))
                    .toArray(Number[]::new)
            )
            .element(2)
            .extracting(n -> new BigDecimal(n.toString()))
            .asInstanceOf(BIG_DECIMAL)
            .isEqualByComparingTo("78");
    }

    @Test
    @DisplayName("Window Function: NTILE for quartiles")
    void testWindowFunctionNtile() {
        var studentName = findAttribute(ctx.studentsRoot, "name");
        var studentGpa = findAttribute(ctx.studentsRoot, "prior_gpa");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(studentName, null), "name"),
                new SelectedExpression(new Path(studentGpa, null), "gpa"),
                new SelectedExpression(
                    new WindowFunction(
                        "NTILE",
                        List.of(new Literal(4)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(studentGpa, null), false))
                        )
                    ),
                    "quartile"
                )
            ), false))
            .orderBy(new OrderBy(new Path(studentGpa, null), false))
            .limit(100L)
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result)
            .isNotEmpty()
            .extracting(r -> r.get("quartile", Number.class).intValue())
            .containsAnyOf(1, 2, 3, 4)
            .allMatch(q -> q >= 1 && q <= 4);
    }

    @Test
    @DisplayName("Window Function: PERCENT_RANK and CUME_DIST")
    void testWindowFunctionPercentRankAndCumeDist() {
        var studentName = findAttribute(ctx.studentsRoot, "name");
        var studentGpa = findAttribute(ctx.studentsRoot, "prior_gpa");
        var studentCountry = findAttribute(ctx.studentsRoot, "country");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(studentName, null), "name"),
                new SelectedExpression(new Path(studentCountry, null), "country"),
                new SelectedExpression(new Path(studentGpa, null), "gpa"),
                new SelectedExpression(
                    new WindowFunction(
                        "PERCENT_RANK",
                        List.of(),
                        new WindowSpec(
                            List.of(new Path(studentCountry, null)),
                            List.of(new OrderBy(new Path(studentGpa, null), false))
                        )
                    ),
                    "percent_rank"
                ),
                new SelectedExpression(
                    new WindowFunction(
                        "CUME_DIST",
                        List.of(),
                        new WindowSpec(
                            List.of(new Path(studentCountry, null)),
                            List.of(new OrderBy(new Path(studentGpa, null), false))
                        )
                    ),
                    "cume_dist"
                )
            ), false))
            .where(new BinaryExpression(
                new Path(studentCountry, null),
                StandardOperator.Binary.EQUALS.identifier(),
                new Literal("Canada")
            ))
            .orderBy(new OrderBy(new Path(studentGpa, null), false))
            .limit(50L)
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result)
            .isNotEmpty()
            .allSatisfy(record -> {
                var percentRank = record.get("percent_rank", Number.class).doubleValue();
                var cumeDist = record.get("cume_dist", Number.class).doubleValue();

                // Both should be between 0 and 1
                assertThat(percentRank).isBetween(0.0, 1.0);
                assertThat(cumeDist).isBetween(0.0, 1.0);
                // CUME_DIST should be >= PERCENT_RANK
                assertThat(cumeDist).isGreaterThanOrEqualTo(percentRank);
            });

        // First record should have percent_rank of 0
        assertThat(result.getFirst().get("percent_rank", Number.class).doubleValue())
            .isEqualTo(0.0);
    }

    @Test
    // @Disabled("Foreign key columns with SingularReferenceOverride are not accessible as BasicAttributes - need to support direct FK column access in aggregations")
    @DisplayName("COUNT(DISTINCT) aggregation")
    void testCountDistinct() {
        // Note: student_id and course_id are converted to SingularReferenceAttributes
        // and are no longer available as BasicAttributes. Need to implement
        // foreign key column access for aggregations.
        var enrollmentStudent = findAttribute(ctx.enrollmentsRoot, "student_id");
        var enrollmentCourse = findAttribute(ctx.enrollmentsRoot, "course_id");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.enrollmentsRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(enrollmentCourse, null), "course_id"),
                new SelectedExpression(
                    new Aggregation("COUNT", List.of(new Path(enrollmentStudent, null)), true),
                    "unique_students"
                ),
                new SelectedExpression(
                    new Aggregation("COUNT", List.of(new Literal("*")), false),
                    "total_enrollments"
                )
            ), false))
            .groupBy(new GroupBy(new Path(enrollmentCourse, null)))
            .having(new BinaryExpression(
                new Aggregation("COUNT", List.of(new Path(enrollmentStudent, null)), true),
                StandardOperator.Binary.GREATER_THAN.identifier(),
                new Literal(5)
            ))
            .orderBy(new OrderBy(
                new Aggregation("COUNT", List.of(new Path(enrollmentStudent, null)), true),
                false
            ))
            .limit(10L)
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result)
            .hasSize(10)
            .allSatisfy(record -> {
                var uniqueStudents = record.get("unique_students", Number.class).longValue();
                var totalEnrollments = record.get("total_enrollments", Number.class).longValue();

                assertThat(uniqueStudents)
                    .isGreaterThan(5)
                    .isLessThanOrEqualTo(totalEnrollments);
            });
    }

    private record TestDataContext(
        Root studentsRoot,
        Root coursesRoot,
        Root enrollmentsRoot,
        Root reviewsRoot
    ) {
    }
}