package com.rorm.engine.handler.window;

import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import com.rorm.query.WindowSpec;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.List;

public final class PercentileContWindow extends AbstractWindowFunction {
    public static final String NAME = "PERCENTILE_CONT";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        if (args.size() != 2) {
            throw new IllegalArgumentException("PERCENTILE_CONT window requires exactly 2 arguments: [fraction, orderExpression]");
        }
        var orderType = ctx.resolve(args.get(1));
        return orderType != null ? orderType : new DataType.NumericType(19, 6);
    }

    @Override
    public Field<?> transform(List<Expression> args, WindowSpec windowSpec, TransformContext ctx) {
        if (args.size() != 2) {
            throw new IllegalArgumentException("PERCENTILE_CONT window requires exactly 2 arguments: [fraction, orderExpression]");
        }
        var fraction = ctx.transform(args.get(0));
        var orderExpr = ctx.transform(args.get(1));
        var partition = transformPartitionFields(windowSpec, ctx);

        var sql = new StringBuilder("percentile_cont({0}) within group (order by {1}) over (");
        var bindings = new ArrayList<Object>();
        bindings.add(fraction);
        bindings.add(orderExpr);
        var bindIndex = 2;

        if (partition != null && partition.length > 0) {
            sql.append("partition by ");
            for (int i = 0; i < partition.length; i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append('{').append(bindIndex).append('}');
                bindings.add(partition[i]);
                bindIndex++;
            }
        }

        sql.append(')');
        return DSL.field(sql.toString(), Double.class, bindings.toArray());
    }
}


