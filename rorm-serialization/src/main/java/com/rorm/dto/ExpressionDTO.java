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
    @JsonSubTypes.Type(value = ExpressionDTO.UnaryExpressionDTO.class, name = "unary"),
    @JsonSubTypes.Type(value = ExpressionDTO.TernaryExpressionDTO.class, name = "ternary"),
    @JsonSubTypes.Type(value = ExpressionDTO.SubqueryDTO.class, name = "subquery"),
    @JsonSubTypes.Type(value = ExpressionDTO.OuterRefDTO.class, name = "outerRef")
})
@JsonClassDescription("Base type for all query expressions. For simple attribute paths, you can use a plain string like 'email' or 'customer.name'.")
public sealed interface ExpressionDTO permits
    ExpressionDTO.PathDTO,
    ExpressionDTO.LiteralDTO,
    ExpressionDTO.FunctionCallDTO,
    ExpressionDTO.AggregationDTO,
    ExpressionDTO.WindowFunctionDTO,
    ExpressionDTO.BinaryExpressionDTO,
    ExpressionDTO.UnaryExpressionDTO,
    ExpressionDTO.TernaryExpressionDTO,
    ExpressionDTO.SubqueryDTO,
    ExpressionDTO.OuterRefDTO {

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

    @JsonClassDescription("A SQL function call (non-aggregate)")
    record FunctionCallDTO(
        @JsonPropertyDescription("Name of the SQL function. Examples: 'UPPER', 'LOWER', 'CONCAT', 'COALESCE', 'SUBSTRING'")
        @JsonProperty(required = true)
        String functionName,

        @JsonPropertyDescription("Arguments to pass to the function.")
        @JsonProperty(required = true)
        List<ExpressionDTO> arguments
    ) implements ExpressionDTO {}

    @JsonClassDescription("An aggregate function (COUNT, SUM, AVG, MIN, MAX)")
    record AggregationDTO(
        @JsonPropertyDescription("Name of the aggregate function: COUNT, SUM, AVG, MIN, MAX")
        @JsonProperty(required = true)
        String functionName,

        @JsonPropertyDescription("Arguments to aggregate. For COUNT(*), use empty list.")
        @JsonProperty(required = true)
        List<ExpressionDTO> arguments,

        @JsonPropertyDescription("Whether to apply DISTINCT before aggregation (e.g., COUNT(DISTINCT x)).")
        boolean distinct
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

    @JsonClassDescription("Unary expression with operator and single operand")
    record UnaryExpressionDTO(
        @JsonPropertyDescription("Unary operator: IS_NULL, IS_NOT_NULL, IS_TRUE, IS_FALSE, NOT, NEGATE")
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

    @JsonClassDescription("Reference to an outer query's attribute for correlated subqueries")
    record OuterRefDTO(
        @JsonPropertyDescription("Depth of the outer query to reference (1 = immediate parent, 2 = grandparent, etc.).")
        @JsonProperty(required = true)
        int depth,

        @JsonPropertyDescription("Dot-separated path to the attribute in the outer query. Examples: 'id', 'customer.name'")
        @JsonProperty(required = true)
        String path
    ) implements ExpressionDTO {}
}
