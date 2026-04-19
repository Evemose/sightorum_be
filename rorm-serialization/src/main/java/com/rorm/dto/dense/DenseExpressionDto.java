package com.rorm.dto.dense;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Expression in a query. Set @type to the expression kind and populate the relevant fields.
    Types: path (path), literal (value), function (functionName, arguments), \
    aggregation (functionName, arguments, distinct, filterWhere), binary (left, operator, right), \
    quantified (left, operator, quantifier, query), \
    unary (operator, operand), ternary (first, operator, second, third), \
    window (functionName, arguments, windowSpec), subquery (query), \
    case (whens, elseExpr).
    Example quantified: {"@type":"quantified","left":{"@type":"path","path":"salary"},"operator":"GREATER_THAN","quantifier":"ANY","query":{...}}
    Example case: {"@type":"case","whens":[{"condition":{"@type":"binary",...},"result":{"@type":"literal","value":"ok"}}],"elseExpr":{"@type":"literal","value":"n/a"}}
    For more references, see <query_structure> section of you system prompt
    """)
public record DenseExpressionDto(
    @JsonProperty(value = "@type", required = true)
    @JsonPropertyDescription("Expression type: path, literal, function, aggregation, binary, quantified, unary, ternary, window, subquery, case")
    String type,

    @JsonProperty
    @JsonPropertyDescription("Dot-separated attribute path. Used by 'path' type.")
    String path,

    @JsonProperty
    @JsonPropertyDescription("Literal value: string, number, boolean, null, or array. Used by 'literal' type.")
    Object value,

    @JsonProperty
    @JsonPropertyDescription("""
        Function or aggregate name. Used by 'function', 'aggregation', and 'window' types.
        For @type=function: String — CONCAT, LOWER, UPPER, LENGTH, TRIM, LTRIM, RTRIM, LEFT, RIGHT, SUBSTRING, REPLACE, POSITION, REVERSE, REPEAT, LPAD, RPAD, INITCAP, SPLIT_PART, REGEXP_REPLACE; \
        Numeric — ABS, CEIL, FLOOR, ROUND, TRUNC, MOD, POWER, SQRT, EXP, LN, LOG, SIGN; \
        Date/Time — NOW, CURRENT_DATE, CURRENT_TIME, DATE_TRUNC, EXTRACT, INTERVAL; \
        Conditional — COALESCE, NULLIF, GREATEST, LEAST, CASE; \
        Special — CAST.
        For @type=aggregation: COUNT, SUM, AVG, MIN, MAX, STDDEV_POP, STDDEV_SAMP, VAR_POP, VAR_SAMP, STRING_AGG, ARRAY_AGG, BOOL_AND, BOOL_OR, CORR, REGR_SLOPE, PERCENTILE_CONT (args: [fraction literal 0..1, order expression]).
        Aggregation filterWhere is supported via field 'filterWhere' and maps to SQL FILTER (WHERE ...).
        For @type=window: ROW_NUMBER, RANK, DENSE_RANK, LAG, LEAD, NTH_VALUE, NTILE (or any aggregate used as window).""")
    String functionName,

    @JsonProperty
    @JsonPropertyDescription("Function arguments. Used by 'function', 'aggregation', and 'window' types.")
    List<DenseExpressionDto> arguments,

    @JsonProperty
    @JsonAlias("comparison")
    @JsonPropertyDescription("""
        Operator name. Used by 'binary', 'unary', and 'ternary' types.
        For @type=binary: comparison — EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, LIKE, IN; \
        logical — AND, OR; arithmetic — ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO.
        For @type=unary: IS_NULL, IS_NOT_NULL, IS_TRUE, IS_FALSE, NOT, NEGATE, EXISTS (operand must be a subquery; NOT EXISTS = NOT(EXISTS(subquery))).
        For @type=ternary: BETWEEN.
        Negated forms (NOT_EQUALS, NOT_LIKE, NOT_IN, NOT BETWEEN, NOT EXISTS) are expressed as unary NOT wrapping the positive form.""")
    String operator,

    @JsonProperty
    @JsonPropertyDescription("Left operand. Used by 'binary' type.")
    DenseExpressionDto left,

    @JsonProperty
    @JsonPropertyDescription("Right operand. Used by 'binary' type.")
    DenseExpressionDto right,

    @JsonProperty
    @JsonPropertyDescription("Quantifier for quantified comparisons: ANY or ALL. Used by 'quantified' type.")
    String quantifier,

    @JsonProperty
    @JsonPropertyDescription("Operand. Used by 'unary' type.")
    DenseExpressionDto operand,

    @JsonProperty
    @JsonPropertyDescription("Apply DISTINCT before aggregation. Used by 'aggregation' type.")
    Boolean distinct,

    @JsonProperty
    @JsonPropertyDescription("Nested query. Used by 'subquery' and 'quantified' types.")
    DenseQueryDto query,

    @JsonProperty
    @JsonPropertyDescription("Optional subquery wrapper expression for compatibility: {\"@type\":\"subquery\",\"query\":{...}}. Used by 'quantified' type.")
    DenseExpressionDto subquery,

    @JsonProperty
    @JsonPropertyDescription("Deprecated. Previously used by removed 'outerRef' type.")
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
    DenseQueryDto.WindowSpecDto windowSpec,

    @JsonProperty
    @JsonPropertyDescription("WHEN/THEN clauses. Used by 'case' type.")
    List<WhenClauseDto> whens,

    @JsonProperty
    @JsonPropertyDescription("ELSE expression. Used by 'case' type.")
    DenseExpressionDto elseExpr,

    @JsonProperty
    @JsonPropertyDescription("FILTER (WHERE ...) condition for 'aggregation'. Example: COUNT(id) FILTER (WHERE active = true).")
    DenseExpressionDto filterWhere
) {

    public static DenseExpressionDto path(String path) {
        return new DenseExpressionDto("path", path, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto literal(Object value) {
        return new DenseExpressionDto("literal", null, value, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto functionCall(String functionName, List<DenseExpressionDto> arguments) {
        return new DenseExpressionDto("function", null, null, functionName, arguments, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto aggregation(String functionName, List<DenseExpressionDto> arguments, boolean distinct) {
        return new DenseExpressionDto("aggregation", null, null, functionName, arguments, null, null, null, null, null, distinct, null, null, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto aggregation(String functionName, List<DenseExpressionDto> arguments, boolean distinct, DenseExpressionDto filterWhere) {
        return new DenseExpressionDto("aggregation", null, null, functionName, arguments, null, null, null, null, null, distinct, null, null, null, null, null, null, null, null, null, filterWhere);
    }

    public static DenseExpressionDto binary(DenseExpressionDto left, String operator, DenseExpressionDto right) {
        return new DenseExpressionDto("binary", null, null, null, null, operator, left, right, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto quantified(DenseExpressionDto left, String comparison, String quantifier, DenseQueryDto query) {
        return new DenseExpressionDto("quantified", null, null, null, null, comparison, left, null, quantifier, null, null, query, null, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto unary(String operator, DenseExpressionDto operand) {
        return new DenseExpressionDto("unary", null, null, null, null, operator, null, null, null, operand, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto ternary(DenseExpressionDto first, String operator, DenseExpressionDto second, DenseExpressionDto third) {
        return new DenseExpressionDto("ternary", null, null, null, null, operator, null, null, null, null, null, null, null, null, first, second, third, null, null, null, null);
    }

    public static DenseExpressionDto window(String functionName, List<DenseExpressionDto> arguments, DenseQueryDto.WindowSpecDto windowSpec) {
        return new DenseExpressionDto("window", null, null, functionName, arguments, null, null, null, null, null, null, null, null, null, null, null, null, windowSpec, null, null, null);
    }

    public static DenseExpressionDto subquery(DenseQueryDto query) {
        return new DenseExpressionDto("subquery", null, null, null, null, null, null, null, null, null, null, query, null, null, null, null, null, null, null, null, null);
    }

    public static DenseExpressionDto caseExpr(List<WhenClauseDto> whens, DenseExpressionDto elseExpr) {
        return new DenseExpressionDto("case", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, whens, elseExpr, null);
    }

    public record WhenClauseDto(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Boolean condition expression.")
        DenseExpressionDto condition,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Result expression when condition is true.")
        DenseExpressionDto result
    ) {}
}
