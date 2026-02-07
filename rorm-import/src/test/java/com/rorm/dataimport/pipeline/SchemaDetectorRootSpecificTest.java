package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.type.DataTypeDetector;
import com.rorm.metamodel.DataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
    SchemaDetector.class,
    NamingStyleDetector.class,
    DataTypeDetector.class,
    FlatDetectionStrategy.class,
    HierarchicalDetectionStrategy.class,
    com.rorm.dataimport.hierarchical.HierarchicalSchemaConverter.class
})
class SchemaDetectorRootSpecificTest {

    @TempDir
    Path tempDir;
    @Autowired
    private SchemaDetector schemaDetector;

    @Test
    void appliesRootSpecificOverrides() throws Exception {
        var studentsFile = tempDir.resolve("students.csv");
        Files.writeString(studentsFile, """
            id,name,age
            1,John,20
            2,Jane,21
            """);

        var teachersFile = tempDir.resolve("teachers.csv");
        Files.writeString(teachersFile, """
            id,name,age
            101,Dr. Smith,45
            102,Prof. Johnson,50
            """);

        var studentsSource = new CsvDataSource(studentsFile);
        var teachersSource = new CsvDataSource(teachersFile);

        var studentOverrides = List.<SchemaOverride>of(
            new SchemaOverride.BasicAttributeOverride(
                "age",
                new DataType.NumericType(2, 0)
            )
        );

        var teacherOverrides = List.<SchemaOverride>of(
            new SchemaOverride.BasicAttributeOverride(
                "age",
                new DataType.NumericType(3, 0)
            )
        );

        var overridesByRoot = Map.of(
            "students", studentOverrides,
            "teachers", teacherOverrides
        );

        var schema = schemaDetector.detectSchema(
            List.of(studentsSource, teachersSource),
            overridesByRoot,
            ","
        );

        var studentRoot = schema.roots().get("students");
        assertThat(studentRoot).isNotNull();
        var studentAge = studentRoot.attributes().get("age");
        assertThat(studentAge).isNotNull();
        assertThat(studentAge.toString()).contains("age");

        var teacherRoot = schema.roots().get("teachers");
        assertThat(teacherRoot).isNotNull();
        var teacherAge = teacherRoot.attributes().get("age");
        assertThat(teacherAge).isNotNull();
        assertThat(teacherAge.toString()).contains("age");
    }

    @Test
    void handlesEmptyOverridesForSomeRoots() throws Exception {
        var csvFile = tempDir.resolve("data.csv");
        Files.writeString(csvFile, """
            id,value
            1,100
            """);

        var dataSource = new CsvDataSource(csvFile);

        var overridesByRoot = Map.of(
            "other_root", List.<SchemaOverride>of(
                new SchemaOverride.BasicAttributeOverride("value", new DataType.StringType())
            )
        );

        var schema = schemaDetector.detectSchema(
            List.of(dataSource),
            overridesByRoot,
            ","
        );

        var dataRoot = schema.roots().get("data");
        assertThat(dataRoot).isNotNull();
        assertThat(dataRoot.attributes()).containsKeys("value");
    }

}