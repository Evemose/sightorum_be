package com.rorm.dto;

import com.fasterxml.jackson.annotation.*;
import com.rorm.query.Operator.BinaryOperator;
import com.rorm.query.Operator.TernaryOperator;
import com.rorm.query.Operator.UnaryOperator;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ExpressionDTO.PathDTO.class, name = "path"),
    @JsonSubTypes.Type(value = ExpressionDTO.LiteralDTO.class, name = "literal"),
    @JsonSubTypes.Type(value = ExpressionDTO.FunctionCallDTO.class, name = "function"),
    @JsonSubTypes.Type(value = ExpressionDTO.AggregationDTO.class, name = "aggregation"),
    @JsonSubTypes.Type(value = ExpressionDTO.WindowFunctionDTO.class, name = "window"),
    @JsonSubTypes.Type(value = ExpressionDTO.BinaryExpressionDTO.class, name = "binary"),
    @JsonSubTypes.Type(value = ExpressionDTO.QuantifiedComparisonDTO.class, name = "quantified"),
    @JsonSubTypes.Type(value = ExpressionDTO.UnaryExpressionDTO.class, name = "unary"),
    @JsonSubTypes.Type(value = ExpressionDTO.TernaryExpressionDTO.class, name = "ternary"),
    @JsonSubTypes.Type(value = ExpressionDTO.SubqueryDTO.class, name = "subquery"),
    @JsonSubTypes.Type(value = ExpressionDTO.CaseExpressionDTO.class, name = "case")
})
@JsonClassDescription("Base type for all query expressions. For simple attribute paths, you can use a plain string like 'email' or 'customer.name'.")
public sealed interface ExpressionDTO permits
    ExpressionDTO.PathDTO,
    ExpressionDTO.LiteralDTO,
    ExpressionDTO.FunctionCallDTO,
    ExpressionDTO.AggregationDTO,
    ExpressionDTO.WindowFunctionDTO,
    ExpressionDTO.BinaryExpressionDTO,
    ExpressionDTO.QuantifiedComparisonDTO,
    ExpressionDTO.UnaryExpressionDTO,
    ExpressionDTO.TernaryExpressionDTO,
    ExpressionDTO.SubqueryDTO,
    ExpressionDTO.CaseExpressionDTO {

    @JsonClassDescription("A path expression referencing an attribute, potentially through nested references")
    record PathDTO(
        @JsonPropertyDescription("Dot-separated path to the attribute. Examples: 'email', 'customer.name', 'order.customer.address.city'")
        @JsonProperty(required = true)
        @JsonAlias("target")
        String path
    ) implements ExpressionDTO {
        @JsonCreator
        public PathDTO {
        }
    }

    @JsonClassDescription("A literal/constant value in the query")
    record LiteralDTO(
        @JsonPropertyDescription("The literal value. Can be string, number, boolean, null, or array for IN clauses.")
        @JsonProperty(required = true)
        Object value
    ) implements ExpressionDTO {
        @JsonCreator
        public LiteralDTO {
        }
    }

    enum QuantifierDTO {
        ANY,
        ALL
    }

    @JsonClassDescription("A SQL function call (non-aggregate)")
    record FunctionCallDTO(
        @JsonPropertyDescription("""
            Name of the SQL function.
            Supported functions:
            - Conditional: COALESCE, NULLIF, GREATEST, LEAST, CASE
            - Date/time: NOW, CURRENT_DATE, CURRENT_TIME, DATE_TRUNC, EXTRACT, INTERVAL
            - String: CONCAT, LOWER, UPPER, LENGTH, TRIM, LTRIM, RTRIM, LEFT, RIGHT, SUBSTRING, REPLACE, POSITION, REVERSE, REPEAT, LPAD, RPAD, INITCAP, SPLIT_PART, REGEXP_REPLACE
            - Numeric: ABS, CEIL, FLOOR, ROUND, TRUNC, MOD, POWER, SQRT, EXP, LN, LOG, SIGN
            - Special: CAST
            """)
        @JsonProperty(required = true)
        String functionName,

        @JsonPropertyDescription("Arguments to pass to the function.")
        @JsonProperty(required = true)
        List<ExpressionDTO> arguments
    ) implements ExpressionDTO {}

    @JsonClassDescription("A window/analytic function with OVER clause")
    record WindowFunctionDTO(
        @JsonPropertyDescription("Name of the window function: ROW_NUMBER, RANK, DENSE_RANK, LAG, LEAD, NTH_VALUE, NTILE, etc.")
        @JsonProperty(required = true)
        String functionName,

        @JsonPropertyDescription("Arguments for functions that require them (e.g., LAG(expr, offset)).")
        @JsonProperty(required = true)
        List<ExpressionDTO> arguments,

        @JsonPropertyDescription("Window specification with PARTITION BY and ORDER BY.")
        @JsonProperty(required = true)
        QueryDTO.WindowSpecDTO windowSpec
    ) implements ExpressionDTO {}

    @JsonClassDescription("""
        Binary expression with left operand, operator, and right operand.
        
        For negated comparison operators, use the NOT unary operator combined with the positive operator:
        - Instead of NOT_EQUALS: use NOT(EQUALS(...))
        - Instead of NOT_LIKE: use NOT(LIKE(...))
        - Instead of NOT_IN: use NOT(IN(...))
        """)
    record BinaryExpressionDTO(
        @JsonPropertyDescription("Left-hand side expression.")
        @JsonProperty(required = true)
        ExpressionDTO left,

        @JsonPropertyDescription("Binary operator: EQUALS, GREATER_THAN, LESS_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL, LIKE, IN, AND, OR, ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO")
        @JsonProperty(required = true)
        BinaryOperator operator,

        @JsonPropertyDescription("Right-hand side expression.")
        @JsonProperty(required = true)
        ExpressionDTO right
    ) implements ExpressionDTO {}

    @JsonClassDescription("""
        An aggregate function with optional FILTER (WHERE ...) clause.
        Supported aggregates: COUNT, SUM, AVG, MIN, MAX, STDDEV_POP, STDDEV_SAMP, VAR_POP, VAR_SAMP,
        STRING_AGG, ARRAY_AGG, BOOL_AND, BOOL_OR, CORR, REGR_SLOPE, PERCENTILE_CONT.
        Example with filterWhere:
        {"@type":"aggregation","functionName":"COUNT","arguments":[{"@type":"path","path":"e.id"}],"distinct":true,
         "filterWhere":{"@type":"binary","left":{"@type":"path","path":"e.active"},"operator":"EQUALS","right":{"@type":"literal","value":true}}}
        """)
    record AggregationDTO(
        @JsonPropertyDescription("Name of aggregate function: COUNT, SUM, AVG, MIN, MAX, STDDEV_POP, STDDEV_SAMP, VAR_POP, VAR_SAMP, STRING_AGG, ARRAY_AGG, BOOL_AND, BOOL_OR, CORR, REGR_SLOPE, PERCENTILE_CONT")
        @JsonProperty(required = true)
        String functionName,

        @JsonPropertyDescription("Arguments to aggregate. For COUNT(*), use empty list. PERCENTILE_CONT expects [fraction literal in 0..1, order expression].")
        @JsonProperty(required = true)
        List<ExpressionDTO> arguments,

        @JsonPropertyDescription("Whether to apply DISTINCT before aggregation (e.g., COUNT(DISTINCT x)).")
        boolean distinct,

        @JsonPropertyDescription("Optional FILTER (WHERE ...) condition applied to the aggregate, equivalent to SQL: AGG(expr) FILTER (WHERE condition).")
        @JsonProperty(required = false)
        ExpressionDTO filterWhere
    ) implements ExpressionDTO {
        /**
         * Backward-compatible constructor without filter.
         */
        public AggregationDTO(String functionName, List<ExpressionDTO> arguments, boolean distinct) {
            this(functionName, arguments, distinct, null);
        }
    }

    @JsonClassDescription("""
        Quantified comparison against a subquery: <comparison> <quantifier> (subquery).
        Example: {"@type":"quantified","left":{"@type":"path","path":"salary"},"comparison":"GREATER_THAN","quantifier":"ALL","subquery":{"@type":"subquery","query":{...}}}
        """)
    record QuantifiedComparisonDTO(
        @JsonPropertyDescription("Left-hand side expression.")
        @JsonProperty(required = true)
        ExpressionDTO left,

        @JsonPropertyDescription("Comparison operator: EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL")
        @JsonProperty(required = true)
        BinaryOperator comparison,

        @JsonPropertyDescription("Quantifier: ANY or ALL")
        @JsonProperty(required = true)
        QuantifierDTO quantifier,

        @JsonPropertyDescription("Right-hand side scalar subquery.")
        @JsonProperty(required = true)
        SubqueryDTO subquery
    ) implements ExpressionDTO {}

    @JsonClassDescription("Unary expression with operator and single operand")
    record UnaryExpressionDTO(
        @JsonPropertyDescription("Unary operator: IS_NULL, IS_NOT_NULL, IS_TRUE, IS_FALSE, NOT, NEGATE, EXISTS")
        @JsonProperty(required = true)
        UnaryOperator operator,

        @JsonPropertyDescription("The operand expression.")
        @JsonProperty(required = true)
        ExpressionDTO operand
    ) implements ExpressionDTO {}

    @JsonClassDescription("""
        Ternary expression for BETWEEN operations.
        
        For NOT BETWEEN, use the NOT unary operator combined with BETWEEN:
        - Instead of NOT_BETWEEN: use NOT(BETWEEN(value, lower, upper))
        """)
    record TernaryExpressionDTO(
        @JsonPropertyDescription("The expression to test.")
        @JsonProperty(required = true)
        ExpressionDTO first,

        @JsonPropertyDescription("Ternary operator: BETWEEN")
        @JsonProperty(required = true)
        TernaryOperator operator,

        @JsonPropertyDescription("Lower bound for BETWEEN.")
        @JsonProperty(required = true)
        ExpressionDTO second,

        @JsonPropertyDescription("Upper bound for BETWEEN.")
        @JsonProperty(required = true)
        ExpressionDTO third
    ) implements ExpressionDTO {}

    @JsonClassDescription("A subquery that can be used as an expression (scalar subquery, IN subquery, EXISTS)")
    record SubqueryDTO(
        @JsonPropertyDescription("The nested query.")
        @JsonProperty(required = true)
        QueryDTO query
    ) implements ExpressionDTO {
        @JsonCreator
        public SubqueryDTO {
        }
    }

    @JsonClassDescription("A single WHEN condition THEN result clause inside a CASE expression")
    record WhenClauseDTO(
        @JsonPropertyDescription("The boolean condition expression.")
        @JsonProperty(required = true)
        ExpressionDTO condition,

        @JsonPropertyDescription("The result expression when condition is true.")
        @JsonProperty(required = true)
        ExpressionDTO result
    ) {}

    @JsonClassDescription("""
        CASE WHEN expression with structured WHEN/THEN/ELSE clauses.
        Example: {"@type":"case","whens":[{"condition":{"@type":"binary",...},"result":{"@type":"literal","value":"high"}}],"elseExpr":{"@type":"literal","value":"low"}}
        """)
    record CaseExpressionDTO(
        @JsonPropertyDescription("List of WHEN/THEN clauses (at least one required).")
        @JsonProperty(required = true)
        List<WhenClauseDTO> whens,

        @JsonPropertyDescription("Optional ELSE expression returned when no WHEN condition matches.")
        @JsonProperty(required = false)
        ExpressionDTO elseExpr
    ) implements ExpressionDTO {}
}
