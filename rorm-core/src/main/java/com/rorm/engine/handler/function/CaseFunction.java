package com.rorm.engine.handler.function;

import com.rorm.engine.handler.BuiltInFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class CaseFunction implements BuiltInFunctionHandler {
    public static final String NAME = "CASE";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        // CASE returns type of the first THEN clause (second argument)
        // Arguments are: condition1, then1, condition2, then2, ..., [else]
        if (args != null && args.size() >= 2) {
            return ctx.resolveSecondArg(args, null);
        }
        return null;
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        // Arguments are: condition1, then1, condition2, then2, ..., [else]
        var fields = ctx.transformAll(args);
        if (fields.length < 2) {
            throw new IllegalArgumentException("CASE requires at least 2 arguments");
        }

        var caseStep = DSL.<Object>when(DSL.condition((Field<Boolean>) fields[0]), fields[1]);

        int i = 2;
        while (i + 1 < fields.length) {
            caseStep = caseStep.when(DSL.condition((Field<Boolean>) fields[i]), fields[i + 1]);
            i += 2;
        }

        if (i < fields.length) {
            return caseStep.otherwise(fields[i]);
        }

        return caseStep;
    }
}
