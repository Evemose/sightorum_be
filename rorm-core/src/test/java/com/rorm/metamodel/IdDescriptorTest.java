package com.rorm.metamodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IdDescriptor")
class IdDescriptorTest {

    @Test
    @DisplayName("longId creates BIGINT descriptor with correct properties")
    void longIdCreatesCorrectDescriptor() {
        var idDescriptor = IdDescriptor.longId("users");

        assertThat(idDescriptor.columnName()).isEqualTo("id");
        assertThat(idDescriptor.dataType())
            .isInstanceOf(DataType.NumericType.class)
            .extracting("precision", "scale")
            .containsExactly(19, 0);
        assertThat(idDescriptor.idAttribute())
            .isNotNull()
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("id");
                assertThat(attr.location().table()).isEqualTo("users");
                assertThat(attr.location().column()).isEqualTo("id");
            });
    }

    @Test
    @DisplayName("stringId creates VARCHAR descriptor with correct properties")
    void stringIdCreatesCorrectDescriptor() {
        var idDescriptor = IdDescriptor.stringId("products");

        assertThat(idDescriptor.columnName()).isEqualTo("id");
        assertThat(idDescriptor.dataType())
            .isInstanceOf(DataType.StringType.class);
        assertThat(idDescriptor.idAttribute())
            .isNotNull()
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("id");
                assertThat(attr.location().table()).isEqualTo("products");
                assertThat(attr.location().column()).isEqualTo("id");
            });
    }

    @Test
    @DisplayName("custom ID descriptor with UUID type")
    void customUuidDescriptor() {
        var uuidAttribute = new BasicAttribute(
            "uuid",
            new AttributeLocation("entities", "entity_uuid"),
            new DataType.StringType()
        );
        var idDescriptor = new IdDescriptor(uuidAttribute);

        assertThat(idDescriptor.columnName()).isEqualTo("entity_uuid");
        assertThat(idDescriptor.dataType()).isInstanceOf(DataType.StringType.class);
        assertThat(idDescriptor.idAttribute()).isEqualTo(uuidAttribute);
    }

    @Test
    @DisplayName("custom ID descriptor with small integer type")
    void customSmallIntDescriptor() {
        var smallIntAttribute = new BasicAttribute(
            "id",
            new AttributeLocation("categories", "category_id"),
            new DataType.NumericType(4, 0) // SMALLINT
        );
        var idDescriptor = new IdDescriptor(smallIntAttribute);

        assertThat(idDescriptor.columnName()).isEqualTo("category_id");
        assertThat(idDescriptor.dataType())
            .isInstanceOf(DataType.NumericType.class)
            .extracting("precision", "scale")
            .containsExactly(4, 0);
    }
}