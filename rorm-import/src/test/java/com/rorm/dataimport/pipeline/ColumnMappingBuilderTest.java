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
}
