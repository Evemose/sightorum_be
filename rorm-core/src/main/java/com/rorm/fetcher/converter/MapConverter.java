package com.rorm.fetcher.converter;

import com.rorm.fetcher.Row;
import com.rorm.fetcher.RowConverter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Converts a Row to a Map<String, Object>.
 */
public class MapConverter implements RowConverter<Map<String, Object>> {

    @Override
    public Map<String, Object> convert(Row row, Set<String> consumedColumns) {
        var result = new LinkedHashMap<String, Object>();

        for (var columnName : row.getColumnNames()) {
            result.put(columnName, row.get(columnName));
            consumedColumns.add(columnName);
        }

        return result;
    }
}
