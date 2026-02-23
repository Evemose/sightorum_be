package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.source.ImportDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for importing course management system data.
 * Tests the import of a complex multi-table dataset with relationships:
 * - Students: 500 records with personal information
 * - Courses: 100 course offerings
 * - Enrollments: 2000 student-course relationships
 * - Attendance: 5000 attendance records
 * - Reviews: 1153 course reviews
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class CoursesDataIntegrationTest extends AbstractImportTest {

    @Autowired
    private DataSource dataSource;

    // Phase 1: Test basic entity import without relationships
    @Test
    @DisplayName("imports students and courses base entities")
    void importBasicEntities() throws Exception {
        var studentsFile = loadResourceFile("data/cources/students.csv");
        var coursesFile = loadResourceFile("data/cources/courses.csv");

        var studentsSource = new CsvDataSource(studentsFile);
        var coursesSource = new CsvDataSource(coursesFile);

        var studentsOverrides = List.<SchemaOverride>of(
            new SchemaOverride.IdAttributeOverride("id", "student_id", null)
        );
        var coursesOverrides = List.<SchemaOverride>of(
            new SchemaOverride.IdAttributeOverride("id", "course_id", null)
        );

        var detectionResult = modelSpaceDetector.detect(
            List.of(studentsSource, coursesSource),
            Map.of(
                "students", studentsOverrides,
                "courses", coursesOverrides
            ),
            ","
        );
        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(studentsSource, coursesSource), detectionResult);
        var result = awaitImportCompletion(dataImportPipeline.importData(request));

        assertThat(result.totalRowsImported()).isEqualTo(600); // 500 students + 100 courses

        verifyStudentCount(schema);
        verifyCourseCount(schema);

        verifySpecificStudent(schema, "STU00001", "Allison Hill");
        verifySpecificCourse(schema, "CRS0001", "Mathematics 381");

        studentsSource.close();
        coursesSource.close();
    }

    private Path loadResourceFile(String resourcePath) throws IOException {
        return new ClassPathResource(resourcePath).getFile().toPath();
    }

    // Helper methods for data verification
    private void verifyStudentCount(String schema) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".students"
            );
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(500);
        }
    }

    private void verifyCourseCount(String schema) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".courses"
            );
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(100);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void verifySpecificStudent(String schema, String studentId, String expectedName) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery(
                "SELECT name FROM " + schema + ".students WHERE student_id = '" + studentId + "'"
            );
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("name")).isEqualTo(expectedName);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void verifySpecificCourse(String schema, String courseId, String expectedName) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery(
                "SELECT course_name FROM " + schema + ".courses WHERE course_id = '" + courseId + "'"
            );
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("course_name")).isEqualTo(expectedName);
        }
    }

    // Phase 2: Test relationships import (enrollments)
    @Test
    @DisplayName("imports enrollments with foreign key relationships")
    void importEnrollments() throws Exception {
        var studentsFile = loadResourceFile("data/cources/students.csv");
        var coursesFile = loadResourceFile("data/cources/courses.csv");
        var enrollmentsFile = loadResourceFile("data/cources/enrollments.csv");

        var studentsSource = new CsvDataSource(studentsFile);
        var coursesSource = new CsvDataSource(coursesFile);
        var enrollmentsSource = new CsvDataSource(enrollmentsFile);

        // Define ID columns and relationships
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

        var overridesByRoot = Map.of(
            "students", studentsOverrides,
            "courses", coursesOverrides,
            "enrollments", enrollmentsOverrides
        );

        var detectionResult = modelSpaceDetector.detect(
            List.of(studentsSource, coursesSource, enrollmentsSource),
            overridesByRoot,
            ","
        );

        // Import
        var schema = getSchemaName();
        var request = new ImportRequest(schema,
            List.of(studentsSource, coursesSource, enrollmentsSource),
            detectionResult);
        var result = awaitImportCompletion(dataImportPipeline.importData(request));

        // Verify import totals
        assertThat(result.totalRowsImported()).isEqualTo(2600); // 500 + 100 + 2000

        // Verify enrollments with foreign key integrity
        verifyEnrollmentCount(schema);
        verifyEnrollmentForeignKeys(schema);

        // Verify specific enrollment relationships
        verifyHighAttendanceStudents(schema, 90.0, 300); // At least 300 students with >90% attendance

        studentsSource.close();
        coursesSource.close();
        enrollmentsSource.close();
    }

    private void verifyEnrollmentCount(String schema) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".enrollments"
            );
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2000);
        }
    }

    private void verifyEnrollmentForeignKeys(String schema) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            // Count enrollments with valid foreign keys
            var rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".enrollments e " +
                "WHERE e.student_id IN (SELECT student_id FROM " + schema + ".students) " +
                "AND e.course_id IN (SELECT course_id FROM " + schema + ".courses)"
            );
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2000); // All enrollments should have valid FKs
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void verifyHighAttendanceStudents(String schema, double minRate, int minCount) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".enrollments " +
                "WHERE attendance_rate > " + minRate
            );
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(minCount);
        }
    }

    // Phase 3: Test complete dataset with all relationships
    @Test
    @DisplayName("imports complete course dataset with all relationships")
    void importCompleteDataset() throws Exception {
        var dataSources = loadAllDataSources();
        var overridesByRoot = createAllOverrides();

        var detectionResult = modelSpaceDetector.detect(
            dataSources,
            overridesByRoot,
            ","
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, dataSources, detectionResult);
        var result = awaitImportCompletion(dataImportPipeline.importData(request));

        // Verify total import count
        assertThat(result.totalRowsImported()).isEqualTo(8753); // 500 + 100 + 2000 + 5000 + 1153

        // Verify each table
        verifyCompleteDataIntegrity(schema);

        // Close all sources
        dataSources.forEach(source -> {
            try {
                source.close();
            } catch (Exception _) {
                // ignore
            }
        });
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

    private void verifyCompleteDataIntegrity(String schema) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {

            // Verify attendance records
            var attendanceRs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".attendance"
            );
            attendanceRs.next();
            assertThat(attendanceRs.getInt(1)).isEqualTo(5000);

            // Verify all attendance records have valid enrollment IDs
            var validAttendanceRs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".attendance a " +
                "WHERE a.enrollment_id IN (SELECT enrollment_id FROM " + schema + ".enrollments)"
            );
            validAttendanceRs.next();
            assertThat(validAttendanceRs.getInt(1)).isEqualTo(5000);

            // Verify reviews
            var reviewsRs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".reviews"
            );
            reviewsRs.next();
            assertThat(reviewsRs.getInt(1)).isEqualTo(1153);

            // Count reviews with rating >= 4
            var goodReviewsRs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".reviews WHERE rating >= 4"
            );
            goodReviewsRs.next();
            assertThat(goodReviewsRs.getInt(1)).isGreaterThan(500); // At least ~half should be good reviews

            // Verify review foreign keys
            var validReviewsRs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".reviews r " +
                "WHERE r.enrollment_id IN (SELECT enrollment_id FROM " + schema + ".enrollments) " +
                "AND r.student_id IN (SELECT student_id FROM " + schema + ".students) " +
                "AND r.course_id IN (SELECT course_id FROM " + schema + ".courses)"
            );
            validReviewsRs.next();
            assertThat(validReviewsRs.getInt(1)).isEqualTo(1153);
        }
    }

    @Test
    @DisplayName("verifies nullable fields are handled correctly")
    void testNullableFields() throws Exception {
        var studentsFile = loadResourceFile("data/cources/students.csv");
        var studentsSource = new CsvDataSource(studentsFile);

        var detectionResult = modelSpaceDetector.detect(
            List.of(studentsSource),
            Map.of(),
            ","
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(studentsSource), detectionResult);
        var result = awaitImportCompletion(dataImportPipeline.importData(request));

        assertThat(result.totalRowsImported()).isEqualTo(500);

        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {

            // STU00001 has no email (first line shows empty)
            var nullEmailRs = stmt.executeQuery(
                "SELECT email FROM " + schema + ".students WHERE student_id = 'STU00001'"
            );
            nullEmailRs.next();
            var email = nullEmailRs.getString("email");
            assertThat(email == null || email.isEmpty()).isTrue();

            // Count total null/empty emails
            var countNullEmailRs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".students WHERE email IS NULL OR email = ''"
            );
            countNullEmailRs.next();
            assertThat(countNullEmailRs.getInt(1)).isGreaterThan(0); // Some students have no email

            // Count students with valid emails
            var countValidEmailRs = stmt.executeQuery(
                "SELECT COUNT(*) FROM " + schema + ".students WHERE email IS NOT NULL AND email != '' AND email LIKE '%@%'"
            );
            countValidEmailRs.next();
            assertThat(countValidEmailRs.getInt(1)).isGreaterThan(300); // Most students have emails
        }

        studentsSource.close();
    }
}
