package com.rorm.engine;

import com.rorm.metamodel.AttributeLocation;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.IdDescriptor;
import com.rorm.metamodel.Root;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Path;
import com.rorm.query.StandardOperator;
import com.rorm.testutil.TestHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SUBTRACT type resolution")
class SubtractTypeResolutionTest {

    @Test
    @DisplayName("date minus date resolves to integer-like numeric type")
    void dateMinusDateResolvesToNumericDays() {
        var startDate = new BasicAttribute("start_date", new AttributeLocation("events", "start_date"), new DataType.DateType());
        var endDate = new BasicAttribute("end_date", new AttributeLocation("events", "end_date"), new DataType.DateType());
        var root = new Root("events", List.of(startDate, endDate), IdDescriptor.longId("events"));

        var resolver = new ExpressionTypeResolver(TestHandlerRegistry.createWithAllBuiltIns());
        var expr = BinaryExpression.of(
            new Path(endDate, null),
            StandardOperator.Binary.SUBTRACT,
            new Path(startDate, null)
        );

        var resolved = resolver.resolveWithRoot(expr, root);

        assertThat(resolved)
            .isInstanceOf(DataType.NumericType.class)
            .extracting("precision", "scale")
            .containsExactly(19, 0);
    }
}


