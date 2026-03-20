package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.metamodel.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ColumnMappingBuilder")
class ColumnMappingBuilderTest {

    @Test
    @DisplayName("maps collection attribute peak_season_months as list-typed column mapping")
    void mapsCollectionAttributeAsListTypedColumnMapping() {
        var attributes = new LinkedHashMap<String, DetectedAttribute>();
        attributes.put("id", new DetectedAttribute.Basic(
            "id",
            new SourceMapping("products", "id"),
            new DataType.NumericType(19, 0)
        ));
        attributes.put("peakSeasonMonths", new DetectedAttribute.Collection(
            "peakSeasonMonths",
            new SourceMapping("products", "peak_season_months"),
            ",",
            new DataType.NumericType(19, 0)
        ));

        var root = new SchemaDetector.DetectedRoot(
            "products",
            "products",
            attributes,
            new SchemaDetector.DetectedIdColumn("id", "id", new DataType.NumericType(19, 0))
        );

        var mappings = new ColumnMappingBuilder().buildMappings(root);

        assertThat(mappings)
            .filteredOn(mapping -> mapping.dbColumnName().equals("peak_season_months"))
            .singleElement()
            .extracting(ColumnMapping::dataType)
            .isInstanceOf(DataType.ListType.class)
            .extracting(type -> ((DataType.ListType) type).elementType())
            .isInstanceOf(DataType.NumericType.class);
    }

    @Test
    @DisplayName("OneToOneRoot produces FK column mapping in parent root")
    void oneToOneRootProducesFkColumnMapping() {
        var contactSubAttrs = new LinkedHashMap<String, DetectedAttribute>();
        contactSubAttrs.put("id", new DetectedAttribute.Basic(
            "id",
            new SourceMapping("employees", "contact_id"),
            new DataType.NumericType(19, 0)
        ));
        contactSubAttrs.put("email", new DetectedAttribute.Basic(
            "email",
            new SourceMapping("employees", "contact_email"),
            new DataType.StringType()
        ));
        contactSubAttrs.put("phone", new DetectedAttribute.Basic(
            "phone",
            new SourceMapping("employees", "contact_phone"),
            new DataType.StringType()
        ));

        var attributes = new LinkedHashMap<String, DetectedAttribute>();
        attributes.put("name", new DetectedAttribute.Basic(
            "name",
            new SourceMapping("employees", "name"),
            new DataType.StringType()
        ));
        attributes.put("contact", new DetectedAttribute.OneToOneRoot(
            "contact", "contact", contactSubAttrs
        ));

        var root = new SchemaDetector.DetectedRoot(
            "employees",
            "employees",
            attributes,
            new SchemaDetector.DetectedIdColumn("id", "id", new DataType.NumericType(19, 0))
        );

        var mappings = new ColumnMappingBuilder().buildMappings(root);

        assertThat(mappings)
            .filteredOn(mapping -> mapping.dbColumnName().equals("contact_id"))
            .as("Parent root must include FK column mapping for OneToOneRoot")
            .singleElement()
            .satisfies(mapping -> {
                assertThat(mapping.sourceColumn()).isEqualTo("contact_id");
                assertThat(mapping.dataType()).isInstanceOf(DataType.NumericType.class);
            });
    }
}
