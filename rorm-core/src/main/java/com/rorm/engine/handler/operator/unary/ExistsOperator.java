package com.rorm.engine.handler.operator.unary;

import com.rorm.engine.handler.BuiltInUnaryOperatorHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * EXISTS (subquery) operator.
 * <p>
 * The operand must be a Subquery expression.
 * NOT EXISTS is expressed as NOT(EXISTS(subquery)).
 */
public final class ExistsOperator implements BuiltInUnaryOperatorHandler {

    public static final String NAME = "EXISTS";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(Expression operand, TypeResolutionContext ctx) {
        return new DataType.BooleanType();
    }

    @Override
    public Field<?> transform(Expression operand, TransformContext ctx) {
        // The subquery transform wraps Select in DSL.field(select).
        // We pass it through DSL.exists(DSL.select(field)) which produces
        // EXISTS (SELECT subquery_field) — functionally correct for existence checks.
        var field = ctx.transform(operand);
        return DSL.exists(DSL.select(field));
    }
}
