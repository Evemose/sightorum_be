package com.rorm.query;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * First-class CASE WHEN expression with structured WHEN/THEN/ELSE clauses.
 * <p>
 * Equivalent SQL:
 * <pre>
 * CASE
 *   WHEN condition1 THEN result1
 *   WHEN condition2 THEN result2
 *   ELSE elseResult
 * END
 * </pre>
 *
 * @param whens    list of WHEN/THEN clauses (at least one required)
 * @param elseExpr optional ELSE expression
 */
public record CaseExpression(
    List<WhenClause> whens,
    @Nullable Expression elseExpr
) implements Expression {

    public CaseExpression {
        if (whens == null || whens.isEmpty()) {
            throw new IllegalArgumentException("CASE requires at least one WHEN clause");
        }
    }

    // Convenience factories
    public static CaseExpression of(List<WhenClause> whens, @Nullable Expression elseExpr) {
        return new CaseExpression(whens, elseExpr);
    }

    public static CaseExpression of(List<WhenClause> whens) {
        return new CaseExpression(whens, null);
    }

    /**
     * A single WHEN condition THEN result clause.
     */
    public record WhenClause(
        Expression condition,
        Expression result
    ) {
        public WhenClause {
            if (condition == null) {
                throw new IllegalArgumentException("WHEN condition is required");
            }
            if (result == null) {
                throw new IllegalArgumentException("THEN result is required");
            }
        }
    }
}
