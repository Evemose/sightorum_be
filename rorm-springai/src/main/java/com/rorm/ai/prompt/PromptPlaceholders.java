package com.rorm.ai.prompt;

import com.rorm.ai.MetamodelContextBuilder;
import com.rorm.metamodel.ModelSpace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Resolves common placeholders in any system prompt template.
 * Both {@link AgentPromptBuilder} and swarm prompts use these.
 *
 * <p>Supported placeholders:
 * <ul>
 *   <li>{@code {{METAMODEL}}} — entity/attribute schema description</li>
 *   <li>{@code {{QUERY_STRUCTURE}}} — query construction reference</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PromptPlaceholders {

    public static final String QUERY_STRUCTURE = """
        # QUERY CONSTRUCTION REFERENCE
        
        Queries use JSON structure with dot-separated paths through the metamodel.
        
        ## Query Object
        ```json
        {
          "from": "tableName",
          "fromAlias": "t",
          "selector": { /* what to SELECT */ },
          "joins": [{ /* explicit joins */ }],
          "where": { /* filter expression */ },
          "groupBy": { "expressions": [...] },
          "having": { /* post-aggregation filter */ },
          "orderBy": [{ "expression": {...}, "ascending": true }],
          "limit": 100,
          "offset": 0
        }
        ```
        
        ## Path Resolution
        1. First segment: alias OR attribute name
           - Matches join/FROM alias -> starts from that aliased root
           - Otherwise -> attribute of implicit FROM root
        2. Subsequent segments: navigate through metamodel
           - CompositeAttribute -> nested attributes
           - ReferenceAttribute -> target root's attributes
        
        Examples: `"name"` (FROM root), `"c.name"` (aliased), `"address.city"` (composite), `"o.customer.name"` (reference)
        
        ## Expression Types (@type)
        - `"path"`: `{"@type":"path", "path":"customer.name"}`
        - `"literal"`: `{"@type":"literal", "value": 123}`
        - `"binary"`: `{"@type":"binary", "left":{...}, "operator":"EQUALS", "right":{...}}`
        - `"unary"`: `{"@type":"unary", "operator":"NOT", "operand":{...}}`
        - `"ternary"`: `{"@type":"ternary", "first":{...}, "operator":"BETWEEN", "second":{...}, "third":{...}}`
        - `"aggregation"`: `{"@type":"aggregation", "functionName":"COUNT", "arguments":[], "distinct":false}`
        - `"function"`: `{"@type":"function", "functionName":"UPPER", "arguments":[...]}`
        - `"window"`: Window function with OVER clause
        - `"subquery"`: Nested query
        - `"outerRef"`: Correlated subquery reference
        
        ## Selector Types (@type)
        - `"root"`: SELECT * from entity
        - `"single"`: SELECT one expression with optional alias
        - `"multi"`: SELECT multiple expressions with aliases
        
        ## Operators
        
        **Binary**: EQUALS, GREATER_THAN, LESS_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL, LIKE, IN, AND, OR
        Arithmetic: ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO
        
        **Negation**: Use unary NOT wrapping the positive operator
        - NOT_EQUALS: `{"@type":"unary", "operator":"NOT", "operand":{"@type":"binary", "operator":"EQUALS", ...}}`
        - NOT_LIKE: `{"@type":"unary", "operator":"NOT", "operand":{"@type":"binary", "operator":"LIKE", ...}}`
        - NOT_IN: `{"@type":"unary", "operator":"NOT", "operand":{"@type":"binary", "operator":"IN", ...}}`
        
        **Unary**: IS_NULL, IS_NOT_NULL, IS_TRUE, IS_FALSE, NOT, NEGATE
        
        **Ternary**: BETWEEN (for NOT BETWEEN, wrap with NOT unary)
        
        **Aggregates**: COUNT, SUM, AVG, MIN, MAX, STDDEV_POP, STDDEV_SAMP, VAR_POP, VAR_SAMP, STRING_AGG, ARRAY_AGG, BOOL_AND, BOOL_OR
        
        **Functions**:
        String: UPPER, LOWER, TRIM, LTRIM, RTRIM, CONCAT, SUBSTRING, REPLACE, LEFT, RIGHT, REVERSE, LPAD, RPAD, INITCAP, REPEAT, LENGTH, POSITION
        Numeric: ABS, ROUND, FLOOR, CEIL, TRUNC, SIGN, MOD, SQRT, POWER, EXP, LN, LOG
        Date/Time: NOW, CURRENT_DATE, CURRENT_TIME, DATE_TRUNC, EXTRACT
        Conditional: COALESCE, NULLIF, GREATEST, LEAST, CASE
        Special: CAST function (NOT CAST_(TYPE) or something)
        
        ## Example: CAST
        CAST is a function that converts a value to a specified type:
        {"@type": "function", "functionName": "CAST",
         "arguments": [
           value, type
         ]}
        
        ## Very important example: CASE / Conditional Bucketing
        CASE is a function with pairs of (condition, result) arguments, plus a final default:
        {"@type": "function", "functionName": "CASE",
         "arguments": [
           condition1, result1,
           condition2, result2,
           defaultResult
         ]}
        
        For simple bucketing, prefer using analyzeExpression or WHERE filters
        per bucket rather than constructing complex CASE expressions.
        
        **Window**: ROW_NUMBER, RANK, DENSE_RANK, LAG, LEAD, NTH_VALUE, NTILE""";

    private final MetamodelContextBuilder metamodelContextBuilder;

    public String resolve(String template, ModelSpace modelSpace) {
        var withMetamodel = replaceIfPresent(
            template,
            "{{METAMODEL}}",
            () -> metamodelContextBuilder.buildContext(modelSpace)
        );
        return withMetamodel.replace("{{QUERY_STRUCTURE}}", QUERY_STRUCTURE);
    }

    private String replaceIfPresent(String template, String placeholder, Supplier<String> replacement) {
        return template.contains(placeholder) ? template.replace(placeholder, replacement.get()) : template;
    }
}
