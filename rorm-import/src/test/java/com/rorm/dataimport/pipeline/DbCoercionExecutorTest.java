package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedIdColumn;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedRoot;
import com.rorm.dataimport.type.DbLevelCoercion;
import com.rorm.dataimport.type.InvalidValueCoercionStrategy;
import com.rorm.metamodel.DataType.NumericType;
import com.rorm.metamodel.DataType.StringType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Resolution is pure metadata arithmetic, so it is exercised without a database. The fixture mirrors how
 * detection names attributes: a snake_case source header ({@code unit_price}) becomes a camelCase attribute
 * ({@code unitPrice}), so the logical name and the physical column genuinely differ.
 */
@DisplayName("DbCoercionExecutor target resolution")
class DbCoercionExecutorTest {

    private final DbCoercionExecutor executor =
        new DbCoercionExecutor(mock(JdbcTemplate.class), mock(TransactionTemplate.class));
    private final MetamodelConverter metamodelConverter = new MetamodelConverter();

    @Test
    @DisplayName("resolves a db-level backfill against the physical column when the attribute name differs from it")
    void resolvesTargetWhenAttributeNameDiffersFromColumn() {
        var detectedSchema = ordersSchemaWithSnakeCaseColumns();
        var modelSpace = metamodelConverter.convertToModelSpace(detectedSchema);
        var request = importRequestWithCoercion(detectedSchema, "unitPrice", DbLevelCoercion.ForwardFill.INSTANCE);

        var targets = executor.resolveTargets(request, modelSpace);

        assertThat(targets).hasSize(1);
        var target = targets.getFirst();
        assertThat(target.tableName()).isEqualTo("orders");
        assertThat(target.columnName()).isEqualTo("unit_price");
        assertThat(target.dataType()).isInstanceOf(NumericType.class);
        assertThat(target.idColumnName()).isEqualTo("order_id");
        assertThat(target.toSql()).contains("\"unit_price\"").doesNotContain("unitPrice");
    }

    @ParameterizedTest
    @MethodSource("dbLevelStrategies")
    @DisplayName("every db-level strategy resolves to the physical column")
    void everyStrategyResolvesPhysicalColumn(DbLevelCoercion strategy) {
        var detectedSchema = ordersSchemaWithSnakeCaseColumns();
        var modelSpace = metamodelConverter.convertToModelSpace(detectedSchema);
        var request = importRequestWithCoercion(detectedSchema, "unitPrice", strategy);

        var targets = executor.resolveTargets(request, modelSpace);

        assertThat(targets).singleElement()
            .satisfies(target -> assertThat(target.columnName()).isEqualTo("unit_price"));
    }

    static Stream<DbLevelCoercion> dbLevelStrategies() {
        return Stream.of(
            DbLevelCoercion.ForwardFill.INSTANCE,
            DbLevelCoercion.BackwardFill.INSTANCE,
            DbLevelCoercion.UseMean.INSTANCE,
            DbLevelCoercion.UseMedian.INSTANCE,
            DbLevelCoercion.UseMode.INSTANCE
        );
    }

    private ImportRequest importRequestWithCoercion(
        DetectedSchema detectedSchema, String attributeName, InvalidValueCoercionStrategy strategy
    ) {
        var coercions = Map.of(new ImportRequest.AttributeKey("orders", attributeName), strategy);
        return new ImportRequest("target_schema", List.of(), detectedSchema, 1000, coercions);
    }

    private DetectedSchema ordersSchemaWithSnakeCaseColumns() {
        var attributes = Map.<String, DetectedAttribute>of(
            "orderId", new DetectedAttribute.Basic(
                "orderId", new SourceMapping("orders", "order_id"), new NumericType(19, 0)),
            "fullName", new DetectedAttribute.Basic(
                "fullName", new SourceMapping("orders", "full_name"), new StringType()),
            "unitPrice", new DetectedAttribute.Basic(
                "unitPrice", new SourceMapping("orders", "unit_price"), new NumericType(10, 2))
        );
        var root = new DetectedRoot(
            "orders", "orders", attributes,
            new DetectedIdColumn("orderId", "order_id", new NumericType(19, 0))
        );
        return new DetectedSchema(Map.of("orders", root));
    }
}
