package com.rorm.dto.dense;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.LinkedHashSet;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    SELECT clause definition. Set @type and the relevant fields.
    Types: root (rootName), single (expression, alias), multi (expressions).""")
public record DenseSelectorDto(
    @JsonProperty(value = "@type", required = true)
    @JsonPropertyDescription("Selector type: root, single, multi")
    String type,

    @JsonProperty
    @JsonPropertyDescription("Whether to apply DISTINCT.")
    Boolean distinct,

    @JsonProperty
    @JsonPropertyDescription("Root entity name for SELECT *. Used by 'root' type.")
    String rootName,

    @JsonProperty
    @JsonPropertyDescription("Single expression to select. Used by 'single' type.")
    DenseExpressionDto expression,

    @JsonProperty
    @JsonPropertyDescription("Alias for the selected expression. Used by 'single' type.")
    String alias,

    @JsonProperty
    @JsonPropertyDescription("Set of expressions to select. Used by 'multi' type.")
    @JsonDeserialize(as = LinkedHashSet.class)
    Set<DenseQueryDto.SelectedExpressionDto> expressions
) {

    public Boolean distinct() {
        return distinct != null && distinct;
    }

    public static DenseSelectorDto root(String rootName, boolean distinct) {
        return new DenseSelectorDto("root", distinct, rootName, null, null, null);
    }

    public static DenseSelectorDto single(DenseExpressionDto expression, boolean distinct, String alias) {
        return new DenseSelectorDto("single", distinct, null, expression, alias, null);
    }

    public static DenseSelectorDto multi(Set<DenseQueryDto.SelectedExpressionDto> expressions, boolean distinct) {
        return new DenseSelectorDto("multi", distinct, null, null, null, expressions);
    }
}
