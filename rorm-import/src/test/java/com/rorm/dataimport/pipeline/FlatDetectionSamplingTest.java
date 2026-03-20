package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.type.DataTypeDetector;
import com.rorm.metamodel.DataType.CategorcialType;
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
class FlatDetectionSamplingTest {

    @TempDir
    Path tempDir;
    @Autowired
    private SchemaDetector schemaDetector;

    @Test
    void detectsCategoricalTypeFromFirst1000RowsEvenWhenMoreValuesExistLater() throws Exception {
        var csvFile = tempDir.resolve("shipments.csv");

        var csv = new StringBuilder("id,status,refrig_model\n");

        // First 1000 rows: only 3 of 9 refrig_model values
        var earlyModels = List.of("Daikin_RKN", "Carrier_Supra_860", "Thermo_King_T-880R");
        for (int i = 1; i <= 1000; i++) {
            csv.append(i).append(",ACTIVE,").append(earlyModels.get(i % 3)).append("\n");
        }

        // Rows 1001-1600: 6 additional values the detector won't see
        var lateModels = List.of(
            "Daikin_LXE10", "Carrier_Vector_8611MT", "Thermo_King_SLXi",
            "Mitsubishi_TFV", "Zanotti_UFZ", "Hwasung_HT100"
        );
        for (int i = 0; i < lateModels.size(); i++) {
            for (int j = 0; j < 100; j++) {
                csv.append(1001 + i * 100 + j).append(",ACTIVE,").append(lateModels.get(i)).append("\n");
            }
        }

        Files.writeString(csvFile, csv.toString());

        var dataSource = new CsvDataSource(csvFile);
        var schema = schemaDetector.detectSchema(List.of(dataSource), Map.of(), ",");
        var root = schema.roots().get("shipments");

        assertThat(root).isNotNull();
        var refrigModelAttr = root.attributes().get("refrigModel");
        assertThat(refrigModelAttr).isNotNull().isInstanceOf(DetectedAttribute.Basic.class);
        var basicAttr = (DetectedAttribute.Basic) refrigModelAttr;

        // Detection correctly identifies the type as categorical from the sample
        assertThat(basicAttr.dataType()).isInstanceOf(CategorcialType.class);

        // But only the 3 values visible in the first 1000 rows are captured —
        // the post-import CategoricalValueRefresher fills in the rest via SELECT DISTINCT
        var enumType = (CategorcialType) basicAttr.dataType();
        assertThat(enumType.values()).hasSize(3)
            .containsExactlyInAnyOrder("Daikin_RKN", "Carrier_Supra_860", "Thermo_King_T-880R");

        dataSource.close();
    }

    @Test
    void detectsCategoricalTypeEvenWhenNotAllValuesInSample() throws Exception {
        var csvFile = tempDir.resolve("events.csv");

        var csv = new StringBuilder("id,category\n");

        // 3 categories in first 1000 rows, 2 more only after
        var categories = List.of("alpha", "beta", "gamma", "delta", "epsilon");
        for (int i = 1; i <= 2000; i++) {
            String cat = i <= 1000
                ? categories.get(i % 3)
                : categories.get(3 + (i % 2));
            csv.append(i).append(",").append(cat).append("\n");
        }

        Files.writeString(csvFile, csv.toString());

        var dataSource = new CsvDataSource(csvFile);
        var schema = schemaDetector.detectSchema(List.of(dataSource), Map.of(), ",");
        var root = schema.roots().get("events");

        assertThat(root).isNotNull();
        var categoryAttr = root.attributes().get("category");
        assertThat(categoryAttr).isNotNull().isInstanceOf(DetectedAttribute.Basic.class);
        var basicCat = (DetectedAttribute.Basic) categoryAttr;

        // Type is correctly detected as categorical
        assertThat(basicCat.dataType()).isInstanceOf(CategorcialType.class);

        // Sample only captures the 3 values from first 1000 rows
        var enumType = (CategorcialType) basicCat.dataType();
        assertThat(enumType.values()).hasSize(3)
            .containsExactlyInAnyOrder("alpha", "beta", "gamma");

        dataSource.close();
    }
}
