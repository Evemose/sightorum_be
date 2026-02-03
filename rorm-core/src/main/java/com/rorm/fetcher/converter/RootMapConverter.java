package com.rorm.fetcher.converter;

import com.rorm.fetcher.Row;
import com.rorm.fetcher.RowConverter;
import com.rorm.metamodel.Attribute;
import com.rorm.metamodel.CompositeAttribute;
import com.rorm.metamodel.Root;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Converts a Row to a nested Map using Root schema metadata.
 * Supports de-flattening of arbitrarily nested composite attributes.
 */
public class RootMapConverter extends AbstractRootConverter implements RowConverter<Map<String, Object>> {

    public RootMapConverter(Root root) {
        super(root);
    }

    @Override
    @Nullable
    public Map<String, Object> convert(Row row, Set<String> consumed) {
        return extractComposite(new CompositeAttribute(
            "",
            Set.copyOf(root.attributes())
        ), row, consumed);
    }

    @Override
    @Nullable
    protected Map<String, Object> extractComposite(CompositeAttribute composite, Row row, Set<String> consumed) {
        if (!hasAnyColumn(composite, row)) {
            return null;
        }

        var map = new LinkedHashMap<String, Object>();

        for (var attr : composite.attributes()) {
            if (attr instanceof Attribute.SingularAttribute singular) {
                var value = extractAttribute(attr, row, consumed);
                if (value != null || isProjected(singular, row)) {
                    map.put(attr.name(), value);
                }
            }
        }

        return map.isEmpty() ? null : map;
    }
}
