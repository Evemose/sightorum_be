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
        # Query Construction Reference (Current)
        
        Use JSON query objects with expression nodes (`@type`) and dot-separated paths.
        
        ## 1) Query Shape
        ```json
        {
          "from": "employees",
          "fromAlias": "e",
          "selector": {"@type": "single", "expression": {"@type": "path", "path": "e.id"}, "distinct": false, "alias": "id"},
          "joins": [],
          "where": {"@type": "binary", "left": {"@type": "path", "path": "e.active"}, "operator": "EQUALS", "right": {"@type": "literal", "value": true}},
          "groupBy": null,
          "having": null,
          "orderBy": [{"expression": {"@type": "path", "path": "e.createdAt"}, "ascending": false}],
          "limit": 100,
          "offset": 0
        }
        ```
        
        ## 2) Path Resolution
        - First segment is alias-or-attribute.
        - If it matches `fromAlias` or a join alias, path starts from that alias.
        - Otherwise, first segment is treated as an attribute of the implicit FROM root.
        - Then navigate normally through composite/reference attributes.
        
        Valid examples:
        - `"name"` -> attribute of FROM root
        - `"e.name"` -> attribute from alias `e`
        - `"address.city"` -> composite navigation
        - `"o.customer.name"` -> reference navigation
        
        Counterexamples:
        - `"unknownAlias.name"` when no such alias exists
        - `"name.foo"` where `name` is scalar (non-navigable)
        
        ## 3) Selector Types (`@type`)
        - `"root"`: select the root
        - `"single"`: one expression + optional alias
        - `"multi"`: multiple selected expressions
        
        ## 4) Expression Types (`@type`)
        - `"path"`: `{"@type":"path","path":"e.name"}`
        - `"literal"`: `{"@type":"literal","value":123}`
        - `"binary"`: `{"@type":"binary","left":{...},"operator":"EQUALS","right":{...}}`
        - `"unary"`: `{"@type":"unary","operator":"NOT","operand":{...}}`
        - `"ternary"`: `{"@type":"ternary","first":{...},"operator":"BETWEEN","second":{...},"third":{...}}`
        - `"quantified"`: `{"@type":"quantified","left":{...},"comparison":"GREATER_THAN","quantifier":"ANY","subquery":{...}}`
        - `"case"`: `{"@type":"case","whens":[...],"elseExpr":{...}}`
        - `"aggregation"`, `"function"`, `"window"`, `"subquery"`, `"outerRef"`
        
        ## 5) Operators
        Binary:
        - comparison: `EQUALS`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `LIKE`, `IN`
        - logical: `AND`, `OR`
        - arithmetic: `ADD`, `SUBTRACT`, `MULTIPLY`, `DIVIDE`, `MODULO`
        
        Unary:
        - `IS_NULL`, `IS_NOT_NULL`, `IS_TRUE`, `IS_FALSE`, `NOT`, `NEGATE`, `EXISTS`
        
        Ternary:
        - `BETWEEN`
        
        Negation rule:
        - Use unary `NOT` around positive operators (no `NOT_EQUALS`, `NOT_LIKE`, `NOT_IN`, `NOT_BETWEEN` operator names).
        
        ## 6) Quantified Comparisons (ANY/ALL)
        Canonical shape:
        ```json
        {
          "@type": "quantified",
          "left": {"@type": "path", "path": "e.salary"},
          "comparison": "GREATER_THAN",
          "quantifier": "ALL",
          "subquery": {
            "@type": "subquery",
            "query": {
              "from": "departments",
              "fromAlias": "d",
              "selector": {"@type": "single", "expression": {"@type": "path", "path": "d.budget"}, "distinct": false, "alias": null}
            }
          }
        }
        ```
        
        Valid composition:
        ```json
        {"@type":"unary","operator":"NOT","operand":{"@type":"quantified", ... }}
        ```
        
        Counterexamples:
        - Wrong type: `{"@type":"binary","operator":"EQUALS_ANY",...}`
        - Wrong fields for quantified: using `operator`/`query` instead of `comparison`/`subquery`
        - Invalid comparison in quantified: `"comparison":"AND"`
        - Non-subquery RHS encoded as quantified subquery payload
        
        ## 7) CASE WHEN (Structured)
        Canonical shape:
        ```json
        {
          "@type": "case",
          "whens": [
            {
              "condition": {"@type":"binary","left":{"@type":"path","path":"e.salary"},"operator":"GREATER_THAN","right":{"@type":"literal","value":100000}},
              "result": {"@type":"literal","value":"high"}
            },
            {
              "condition": {"@type":"binary","left":{"@type":"path","path":"e.salary"},"operator":"GREATER_THAN","right":{"@type":"literal","value":50000}},
              "result": {"@type":"literal","value":"mid"}
            }
          ],
          "elseExpr": {"@type":"literal","value":"low"}
        }
        ```
        
        Counterexamples:
        - Do not encode CASE as a generic function call
        - `whens` must contain condition/result pairs
        - Non-boolean `condition` expressions are invalid
        
        ## 8) Functions and Aggregations (Explicit List)
        Functions:
        - Conditional: `COALESCE`, `NULLIF`, `GREATEST`, `LEAST`, `CASE`
        - Date/time: `NOW`, `CURRENT_DATE`, `CURRENT_TIME`, `DATE_TRUNC`, `EXTRACT`, `INTERVAL`
        - String: `CONCAT`, `LOWER`, `UPPER`, `LENGTH`, `TRIM`, `LTRIM`, `RTRIM`, `LEFT`, `RIGHT`, `SUBSTRING`, `REPLACE`, `POSITION`, `REVERSE`, `REPEAT`, `LPAD`, `RPAD`, `INITCAP`, `SPLIT_PART`, `REGEXP_REPLACE`
        - Numeric: `ABS`, `CEIL`, `FLOOR`, `ROUND`, `TRUNC`, `MOD`, `POWER`, `SQRT`, `EXP`, `LN`, `LOG`, `SIGN`
        - Special: `CAST`
        
        Aggregations:
        - `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `STDDEV_POP`, `STDDEV_SAMP`, `VAR_POP`, `VAR_SAMP`, `STRING_AGG`, `ARRAY_AGG`, `BOOL_AND`, `BOOL_OR`, `CORR`, `REGR_SLOPE`, `PERCENTILE_CONT`
        
        Aggregation with filterWhere (maps to SQL FILTER clause):
        ```json
        {
          "@type": "aggregation",
          "functionName": "COUNT",
          "arguments": [{"@type":"path","path":"e.id"}],
          "distinct": true,
          "filterWhere": {
            "@type": "binary",
            "left": {"@type":"path","path":"e.active"},
            "operator": "EQUALS",
            "right": {"@type":"literal","value": true}
          }
        }
        ```
        
        Counterexample:
        - `{"@type":"aggregation","functionName":"COUNT","arguments":[...],"filter":{"@type":"binary",...}}` (invalid key `filter`; use `filterWhere`)
        
        ## 9) Final Rules
        - Always use explicit `@type` expression nodes.
        - Prefer structured `quantified` and structured `case` nodes.
        - Use unary `NOT` for negated variants.
        - Keep subqueries scalar where scalar value is expected.
        """;

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
