package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.engine.QueryTransformer;
import com.rorm.metamodel.AliasedRoot;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.Root;
import com.rorm.query.*;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Expression.FunctionCall;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.RootSelector;
import com.rorm.query.Selector.SingleExprSelector;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for executing queries using QueryBuilder and derived metamodel
 * with comprehensive value verification.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class CoursesDataIntegrationQueriesTest extends AbstractImportTest {

    @Autowired
    private QueryTransformer queryTransformer;
    @Autowired
    private DSLContext dslContext;
    private TestDataContext ctx;
    @Autowired
    private MetamodelConverter metamodelConverter;

    CoursesDataIntegrationQueriesTest() {
        super(true);
    }

    @BeforeAll
    void setupData() throws Exception {
        super.setupTestSchema();
        ctx = setupTestData();
    }

    @Test
    @DisplayName("Query 1: Select specific student by ID")
    void testSelectStudentById() {

        // The ID is now properly included in attributes, so we can find it normally
        var studentId = findAttribute(ctx.studentsRoot, "student_id");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new RootSelector(ctx.studentsRoot, false))
            .where(new BinaryExpression(
                new Path(studentId, null),
                StandardOperator.Binary.EQUALS.identifier(),
                new Literal("STU00001")
            ))
            .build();

        var result = queryTransformer.transform(query).fetch();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("student_id")).isEqualTo("STU00001");
        assertThat(result.getFirst().get("name")).isEqualTo("Allison Hill");
        assertThat(result.getFirst().get("age")).isEqualTo(23L);  // age is stored as Long
        assertThat(result.getFirst().get("country")).isEqualTo("Uganda");
        assertThat(result.getFirst().get("city")).isEqualTo("New Roberttown");
        assertThat(result.getFirst().get("prior_gpa", Number.class).doubleValue()).isEqualTo(2.45);  // GPA as BigDecimal
        assertThat(result.getFirst().get("has_scholarship")).isEqualTo(true);

        ctx.cleanUp();
    }

    private TestDataContext setupTestData() throws Exception {
        // Import complete dataset first
        var dataSources = loadAllDataSources();
        var overridesByRoot = createAllOverrides();
        var detectionResult = modelSpaceDetector.detect(dataSources, overridesByRoot, ",");
        var schema = getSchemaName();
        var request = new ImportRequest(schema, dataSources, detectionResult);
        var result = awaitImportCompletion(dataImportPipeline.importData(request));

        assertThat(result.totalRowsImported()).isEqualTo(8753);

        var modelSpace = metamodelConverter.convertToModelSpace(detectionResult);
        var roots = modelSpace.roots();
        Root studentsRoot = findRoot(roots, "students");
        Root coursesRoot = findRoot(roots, "courses");
        Root enrollmentsRoot = findRoot(roots, "enrollments");
        Root reviewsRoot = findRoot(roots, "reviews");

        // Set up query execution infrastructure
        dslContext.execute("SET search_path TO " + schema);

        return new TestDataContext(
            dataSources, studentsRoot, coursesRoot, enrollmentsRoot, reviewsRoot
        );
    }

    private BasicAttribute findAttribute(Root root, String name) {
        return root.attributes().stream()
            .filter(BasicAttribute.class::isInstance)
            .map(BasicAttribute.class::cast)
            .filter(a -> a.location().column().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Attribute not found: " + name));
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
        // ID column overrides for each root
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
            new SchemaOverride.SingularReferenceOverride("enrollmentId", "enrollments"),
            new SchemaOverride.SingularReferenceOverride("studentId", "students")
        );

        var reviewsOverrides = List.<SchemaOverride>of(
            new SchemaOverride.IdAttributeOverride("id", "review_id", null),
            new SchemaOverride.SingularReferenceOverride("enrollmentId", "enrollments"),
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
    @DisplayName("Query 2: Count students with scholarship")
    void testCountStudentsWithScholarship() {

        var studentScholarship = findAttribute(ctx.studentsRoot, "has_scholarship");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new SingleExprSelector(
                new FunctionCall("count", List.of(new Path(studentScholarship, null))),
                false,
                "scholarship_count"
            ))
            .where(new BinaryExpression(
                new Path(studentScholarship, null),
                StandardOperator.Binary.EQUALS.identifier(),
                new Literal(true)
            ))
            .build();

        var result = queryTransformer.transform(query).fetch();
        assertThat(result).hasSize(1);  // Count query returns single row
        assertThat(result.getFirst().get("scholarship_count")).isEqualTo(212L);  // Actual count from data

        ctx.cleanUp();
    }

    @Test
    @DisplayName("Query 3: Students from specific countries with ordering and limit")
    void testStudentsFromSpecificCountries() {

        var studentCountry = findAttribute(ctx.studentsRoot, "country");
        var studentName = findAttribute(ctx.studentsRoot, "name");

        // only 2 students (David Hughes and Patrick Thornton, both from Canada)
        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new RootSelector(ctx.studentsRoot, false))
            .where(new BinaryExpression(
                new Path(studentCountry, null),
                StandardOperator.Binary.IN.identifier(),
                new Literal(List.of("USA", "Canada", "UK"))  // List of countries
            ))
            .orderBy(new OrderBy(new Path(studentName, null), true))
            .limit(5L)
            .build();

        var sql = queryTransformer.transform(query);
        System.out.println("Generated SQL for Query 3: " + sql.getSQL());
        var result = sql.fetch();

        assertThat(result).hasSize(2);

        var names = result.stream().map(r -> (String) r.get("name")).toList();
        assertThat(names).containsExactly("David Hughes", "Patrick Thornton");

        result.forEach(record -> assertThat(record.get("country")).isEqualTo("Canada"));

        ctx.cleanUp();
    }

    @Test
    @DisplayName("Query 4: High achieving students (GPA > 3.5) with custom selector")
    void testHighAchievingStudents() {

        var studentName = findAttribute(ctx.studentsRoot, "name");
        var studentEmail = findAttribute(ctx.studentsRoot, "email");
        var studentGpa = findAttribute(ctx.studentsRoot, "prior_gpa");

        // No limit - verify all 93 students with GPA > 3.5
        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(studentName, null), "student_name"),
                new SelectedExpression(new Path(studentGpa, null), "gpa"),
                new SelectedExpression(new Path(studentEmail, null), "email")
            ), false))
            .where(new BinaryExpression(
                new Path(studentGpa, null),
                StandardOperator.Binary.GREATER_THAN.identifier(),
                new Literal(3.5)
            ))
            .orderBy(new OrderBy(new Path(studentGpa, null), false))
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result).hasSize(93);

        Double previousGpa = null;
        for (var record : result) {
            var gpa = record.get("gpa", Double.class);
            assertThat(gpa).isGreaterThan(3.5);
            if (previousGpa != null) {
                assertThat(gpa).isLessThanOrEqualTo(previousGpa);
            }
            previousGpa = gpa;
        }

        // Verify top student has GPA 3.97 (Alexandra Dominguez from data analysis)
        assertThat(result.getFirst().get("gpa", Number.class).doubleValue()).isEqualTo(3.97);

        ctx.cleanUp();
    }

    @Test
    @DisplayName("Query 5: Enrollments with high attendance rate")
    void testEnrollmentsWithHighAttendance() {

        var enrollmentAttendanceRate = findAttribute(ctx.enrollmentsRoot, "attendance_rate");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.enrollmentsRoot))
            .selector(new RootSelector(ctx.enrollmentsRoot, false))
            .where(new BinaryExpression(
                new Path(enrollmentAttendanceRate, null),
                StandardOperator.Binary.GREATER_THAN_OR_EQUAL.identifier(),
                new Literal(95.0)
            ))
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result).hasSize(1078);

        for (var record : result) {
            var attendance = record.get("attendance_rate", Double.class);
            assertThat(attendance).isGreaterThanOrEqualTo(95.0);
        }

        ctx.cleanUp();
    }

    @Test
    @DisplayName("Query 6: Courses by specific instructor")
    void testCoursesByInstructor() {

        var courseName = findAttribute(ctx.coursesRoot, "course_name");
        var courseSubject = findAttribute(ctx.coursesRoot, "subject");
        var courseFee = findAttribute(ctx.coursesRoot, "course_fee");
        var courseInstructor = findAttribute(ctx.coursesRoot, "instructor_name");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.coursesRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(courseName, null), "course"),
                new SelectedExpression(new Path(courseSubject, null), "subject"),
                new SelectedExpression(new Path(courseFee, null), "fee")
            ), false))
            .where(new BinaryExpression(
                new Path(courseInstructor, null),
                StandardOperator.Binary.EQUALS.identifier(),
                new Literal("Dawn Ellis")
            ))
            .build();

        var result = queryTransformer.transform(query).fetch();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("course")).isEqualTo("Mathematics 381");
        assertThat(result.getFirst().get("subject")).isEqualTo("Mathematics");
        assertThat(result.getFirst().get("fee", Number.class).doubleValue()).isEqualTo(124.39);

        ctx.cleanUp();
    }

    @Test
    @DisplayName("Query 7: Completed enrollments with high grades (>= 90)")
    void testCompletedEnrollmentsWithHighGrades() {

        var enrollmentGrade = findAttribute(ctx.enrollmentsRoot, "final_grade");
        var enrollmentCompleted = findAttribute(ctx.enrollmentsRoot, "completed");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.enrollmentsRoot))
            .selector(new RootSelector(ctx.enrollmentsRoot, false))
            .where(new BinaryExpression(
                new BinaryExpression(
                    new Path(enrollmentGrade, null),
                    StandardOperator.Binary.GREATER_THAN_OR_EQUAL.identifier(),
                    new Literal(90.0)
                ),
                StandardOperator.Binary.AND.identifier(),
                new BinaryExpression(
                    new Path(enrollmentCompleted, null),
                    StandardOperator.Binary.EQUALS.identifier(),
                    new Literal(true)
                )
            ))
            .build();

        var result = queryTransformer.transform(query).fetch();
        var highAchievers = result.size();
        assertThat(highAchievers).isEqualTo(857);

        ctx.cleanUp();
    }

    @Test
    @DisplayName("Query 8: Reviews with rating between 4 and 5")
    void testReviewsWithSpecificRatingRange() {

        var reviewRating = findAttribute(ctx.reviewsRoot, "rating");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.reviewsRoot))
            .selector(new RootSelector(ctx.reviewsRoot, false))
            .where(new Expression.TernaryExpression(
                new Path(reviewRating, null),
                StandardOperator.Ternary.BETWEEN.identifier(),
                new Literal(4),
                new Literal(5)
            ))
            .build();

        var result = queryTransformer.transform(query).fetch();
        // Verify exact count and rating range
        assertThat(result.size()).isEqualTo(664);
        for (var record : result) {
            var rating = record.get("rating", Integer.class);
            assertThat(rating).isBetween(4, 5);
        }

        ctx.cleanUp();
    }

    @Test
    @DisplayName("Query 9: Complex filter with age, GPA, and scholarship conditions")
    void testComplexFilterWithMultipleConditions() {

        var studentAge = findAttribute(ctx.studentsRoot, "age");
        var studentGpa = findAttribute(ctx.studentsRoot, "prior_gpa");
        var studentScholarship = findAttribute(ctx.studentsRoot, "has_scholarship");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new RootSelector(ctx.studentsRoot, false))
            .where(new BinaryExpression(
                new BinaryExpression(
                    new Path(studentAge, null),
                    StandardOperator.Binary.GREATER_THAN_OR_EQUAL.identifier(),
                    new Literal(21)
                ),
                StandardOperator.Binary.AND.identifier(),
                new BinaryExpression(
                    new BinaryExpression(
                        new Path(studentGpa, null),
                        StandardOperator.Binary.GREATER_THAN.identifier(),
                        new Literal(3.0)
                    ),
                    StandardOperator.Binary.OR.identifier(),
                    new BinaryExpression(
                        new Path(studentScholarship, null),
                        StandardOperator.Binary.EQUALS.identifier(),
                        new Literal(true)
                    )
                )
            ))
            .orderBy(OrderBy.desc(new Path(studentAge, null)))
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result).hasSize(209);

        Integer previousAge = null;
        for (var record : result) {
            var age = record.get("age", Integer.class);
            var gpa = record.get("prior_gpa", Double.class);
            var scholarship = record.get("has_scholarship", Boolean.class);

            assertThat(age).isGreaterThanOrEqualTo(21);
            assertThat(gpa > 3.0 || Boolean.TRUE.equals(scholarship)).isTrue();

            if (previousAge != null) {
                assertThat(age).isLessThanOrEqualTo(previousAge);
            }
            previousAge = age;
        }

        // Verify top result is Douglas Heath, age 29, GPA 3.47 (from data analysis)
        var topResult = result.getFirst();
        assertThat(topResult.get("age")).isEqualTo(29L);

        ctx.cleanUp();
    }

    @Test
    @DisplayName("Query 10: Email pattern matching with LIKE operator")
    void testEmailPatternMatching() {

        var studentName = findAttribute(ctx.studentsRoot, "name");
        var studentEmail = findAttribute(ctx.studentsRoot, "email");

        var query = Query.builder()
            .from(AliasedRoot.of(ctx.studentsRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(studentName, null), "name"),
                new SelectedExpression(new Path(studentEmail, null), "email")
            ), false))
            .where(new BinaryExpression(
                new Path(studentEmail, null),
                StandardOperator.Binary.LIKE.identifier(),
                new Literal("%@example.com")
            ))
            .build();

        var result = queryTransformer.transform(query).fetch();

        assertThat(result).hasSize(146);

        for (var record : result) {
            var email = record.get("email", String.class);
            assertThat(email).isNotNull().isNotEmpty();
            assertThat(email).endsWith("@example.com");
        }

        ctx.cleanUp();
    }

    private record TestDataContext(
        List<ImportDataSource> dataSources,
        Root studentsRoot,
        Root coursesRoot,
        Root enrollmentsRoot,
        Root reviewsRoot
    ) {
        void cleanUp() {
            dataSources.forEach(source -> {
                try {
                    source.close();
                } catch (Exception _) {
                }
            });
        }
    }
}