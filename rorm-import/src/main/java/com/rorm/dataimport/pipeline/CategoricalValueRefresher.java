package com.rorm.dataimport.pipeline;

import com.rorm.metamodel.Attribute;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.DataType.CategorcialType;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CategoricalValueRefresher {

    private final JdbcTemplate jdbcTemplate;

    public ModelSpace refresh(String schema, ModelSpace modelSpace) {
        var updatedRoots = modelSpace.roots().stream()
            .map(root -> refreshRoot(schema, root))
            .collect(Collectors.toUnmodifiableSet());
        return new ModelSpace(updatedRoots);
    }

    private Root refreshRoot(String schema, Root root) {
        var updatedAttributes = root.attributes().stream()
            .map(attr -> refreshAttribute(schema, root.primaryTableName(), attr))
            .toList();
        return new Root(root.primaryTableName(), updatedAttributes, root.idDescriptor());
    }

    private Attribute refreshAttribute(String schema, String tableName, Attribute attribute) {
        if (attribute instanceof BasicAttribute(var name, var location, var dataType)
            && dataType instanceof CategorcialType) {
            var actualValues = queryDistinctValues(schema, tableName, location.column());
            return new BasicAttribute(name, location, new CategorcialType(actualValues));
        }
        return attribute;
    }

    private String[] queryDistinctValues(String schema, String tableName, String columnName) {
        var sql = "SELECT DISTINCT %s FROM %s.%s WHERE %s IS NOT NULL ORDER BY %s"
            .formatted(quote(columnName), quote(schema), quote(tableName), quote(columnName), quote(columnName));
        List<String> values = jdbcTemplate.queryForList(sql, String.class);
        return values.toArray(String[]::new);
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
