package com.rorm.engine.handler.function;

import com.rorm.engine.handler.TransformContext;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

/**
 * REGEXP_REPLACE(source, pattern, replacement [, flags]) — replaces substrings matching
 * a POSIX regular expression.
 * <p>
 * Example: {@code REGEXP_REPLACE('Hello 123', '[0-9]+', 'NUM')} → {@code 'Hello NUM'}
 */
public final class RegexpReplaceFunction extends AbstractStringFunction {
    public static final String NAME = "REGEXP_REPLACE";

    @Override
    public String name() {
        return NAME;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        if (args.size() < 3) {
            throw new IllegalArgumentException("REGEXP_REPLACE requires at least 3 arguments: source, pattern, replacement");
        }
        var fields = ctx.transformAll(args);
        var source = (Field<String>) fields[0];
        var pattern = (Field<String>) fields[1];
        var replacement = (Field<String>) fields[2];
        // jOOQ's regexpReplaceAll handles the core 3-arg case
        if (args.size() == 3) {
            return DSL.regexpReplaceAll(source, pattern, replacement);
        }
        // For 4+ args (flags), fall back to generic SQL function
        return DSL.function(NAME, String.class, fields);
    }
}
