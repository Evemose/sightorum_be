package com.rorm.dataimport.pipeline;

import com.rorm.metamodel.*;
import com.rorm.metamodel.DataType.CategorcialType;
import com.rorm.metamodel.DataType.NumericType;
import com.rorm.metamodel.DataType.StringType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"SqlNoDataSourceInspection"})
class CategoricalValueRefresherTest extends AbstractImportTest {

    @Autowired
    private CategoricalValueRefresher refresher;

    @Test
    void refreshesCategoricalValuesFromDatabase() {
        var schema = getSchemaName();
        jdbcTemplate.execute("""
            CREATE TABLE "%s"."shipments" (
                id INT, status TEXT, refrig_model TEXT
            )""".formatted(schema));

        var allModels = List.of(
            "Daikin_RKN", "Carrier_Supra_860", "Thermo_King_T-880R",
            "Daikin_LXE10", "Carrier_Vector_8611MT", "Thermo_King_SLXi",
            "Mitsubishi_TFV", "Zanotti_UFZ", "Hwasung_HT100"
        );
        for (int i = 0; i < allModels.size(); i++) {
            jdbcTemplate.update(
                "INSERT INTO \"%s\".\"shipments\" (id, status, refrig_model) VALUES (?, 'ACTIVE', ?)".formatted(schema),
                i + 1, allModels.get(i));
        }

        // ModelSpace with only 3 values from sample-based detection
        var partialEnum = new CategorcialType(new String[]{"Daikin_RKN", "Carrier_Supra_860", "Thermo_King_T-880R"});
        var idAttr = new BasicAttribute("id", new AttributeLocation("shipments", "id"), new NumericType(19, 0));
        var modelSpace = new ModelSpace(Set.of(new Root(
            "shipments",
            List.of(
                idAttr,
                new BasicAttribute("status", new AttributeLocation("shipments", "status"), new StringType()),
                new BasicAttribute("refrigModel", new AttributeLocation("shipments", "refrig_model"), partialEnum)
            ),
            new IdDescriptor(idAttr)
        )));

        var refreshed = refresher.refresh(schema, modelSpace);

        var root = refreshed.roots().iterator().next();
        var refrigModelAttr = root.attributes().stream()
            .filter(a -> a.name().equals("refrigModel"))
            .findFirst()
            .orElseThrow();

        assertThat(refrigModelAttr).isInstanceOf(BasicAttribute.class);
        var ba = (BasicAttribute) refrigModelAttr;
        assertThat(ba.dataType()).isInstanceOf(CategorcialType.class);

        var enumType = (CategorcialType) ba.dataType();
        assertThat(enumType.values()).hasSize(9)
            .contains("Daikin_RKN", "Carrier_Supra_860", "Thermo_King_T-880R")
            .contains("Daikin_LXE10", "Carrier_Vector_8611MT", "Thermo_King_SLXi")
            .contains("Mitsubishi_TFV", "Zanotti_UFZ", "Hwasung_HT100");

        // Non-categorical attributes remain unchanged
        var statusAttr = root.attributes().stream()
            .filter(a -> a.name().equals("status"))
            .findFirst()
            .orElseThrow();
        assertThat(((BasicAttribute) statusAttr).dataType()).isInstanceOf(StringType.class);
    }
}
