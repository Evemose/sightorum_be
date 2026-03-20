package com.rorm.dto.dense;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Expression in a query. Set @type to the expression kind and populate the relevant fields.
    Types: path (path), literal (value), function (functionName, arguments), \
    aggregation (functionName, arguments, distinct), binary (left, operator, right), \
    unary (operator, operand), ternary (first, operator, second, third), \
    window (functionName, arguments, windowSpec), subquery (query), outerRef (depth, path).""")
public record DenseExpressionDto(
    @JsonProperty(value = "@type", required = true)
    @JsonPropertyDescription("Expression type: path, literal, function, aggregation, binary, unary, ternary, window, subquery, outerRef")
    String type,

    @JsonProperty
    @JsonPropertyDescription("Dot-separated attribute path. Used by 'path' and 'outerRef' types.")
    String path,

    @JsonProperty
    @JsonPropertyDescription("Literal value: string, number, boolean, null, or array. Used by 'literal' type.")
    Object value,

    @JsonProperty
    @JsonPropertyDescription("Function name. Used by 'function', 'aggregation', and 'window' types.")
    String functionName,

    @JsonProperty
    @JsonPropertyDescription("Function arguments. Used by 'function', 'aggregation', and 'window' types.")
    List<DenseExpressionDto> arguments,

    @JsonProperty
    @JsonPropertyDescription("Operator: EQUALS, GREATER_THAN, LESS_THAN, LIKE, IN, AND, OR, IS_NULL, NOT, BETWEEN, etc. Used by 'binary', 'unary', and 'ternary' types.")
    String operator,

    @JsonProperty
    @JsonPropertyDescription("Left operand. Used by 'binary' type.")
    DenseExpressionDto left,

    @JsonProperty
    @JsonPropertyDescription("Right operand. Used by 'binary' type.")
    DenseExpressionDto right,

    @JsonProperty
    @JsonPropertyDescription("Operand. Used by 'unary' type.")
    DenseExpressionDto operand,

    @JsonProperty
    @JsonPropertyDescription("Apply DISTINCT before aggregation. Used by 'aggregation' type.")
    Boolean distinct,

    @JsonProperty
    @JsonPropertyDescription("Nested query. Used by 'subquery' type.")
    DenseQueryDto query,

    @JsonProperty
    @JsonPropertyDescription("Outer query reference depth (1 = parent). Used by 'outerRef' type.")
    Integer depth,

    @JsonProperty
    @JsonPropertyDescription("First operand for ternary. Used by 'ternary' type.")
    DenseExpressionDto first,

    @JsonProperty
    @JsonPropertyDescription("Second operand for ternary. Used by 'ternary' type.")
    DenseExpressionDto second,

    @JsonProperty
    @JsonPropertyDescription("Third operand for ternary. Used by 'ternary' type.")
    DenseExpressionDto third,

    @JsonProperty
    @JsonPropertyDescription("Window specification. Used by 'window' type.")
    DenseQueryDto.WindowSpecDto windowSpec
) {

    public static DenseExpressionDto path(String path) {
        return new DenseExpressionDto("path", path, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto literal(Object value) {
        return new DenseExpressionDto("literal", null, value, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto functionCall(String functionName, List<DenseExpressionDto> arguments) {
        return new DenseExpressionDto("function", null, null, functionName, arguments, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto aggregation(String functionName, List<DenseExpressionDto> arguments, boolean distinct) {
        return new DenseExpressionDto("aggregation", null, null, functionName, arguments, null, null, null, null, distinct, null, null, null, null, null, null);
    }

    public static DenseExpressionDto binary(DenseExpressionDto left, String operator, DenseExpressionDto right) {
        return new DenseExpressionDto("binary", null, null, null, null, operator, left, right, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto unary(String operator, DenseExpressionDto operand) {
        return new DenseExpressionDto("unary", null, null, null, null, operator, null, null, operand, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto ternary(DenseExpressionDto first, String operator, DenseExpressionDto second, DenseExpressionDto third) {
        return new DenseExpressionDto("ternary", null, null, null, null, operator, null, null, null, null, null, null, first, second, third, null);
    }

    public static DenseExpressionDto window(String functionName, List<DenseExpressionDto> arguments, DenseQueryDto.WindowSpecDto windowSpec) {
        return new DenseExpressionDto("window", null, null, functionName, arguments, null, null, null, null, null, null, null, null, null, null, windowSpec);
    }

    public static DenseExpressionDto subquery(DenseQueryDto query) {
        return new DenseExpressionDto("subquery", null, null, null, null, null, null, null, null, null, query, null, null, null, null, null);
    }

    public static DenseExpressionDto outerRef(int depth, String path) {
        return new DenseExpressionDto("outerRef", path, null, null, null, null, null, null, null, null, null, depth, null, null, null, null);
    }
}
