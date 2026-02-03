package com.rorm.fetcher;

import lombok.RequiredArgsConstructor;
import org.jooq.Record;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Row implementation backed by a JOOQ Record.
 */
@RequiredArgsConstructor
public class JooqRow implements Row {

    private final Record record;

    @Override
    @Nullable
    public Object get(String columnName) {
        try {
            return record.get(columnName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    @Nullable
    public <T> T get(String columnName, Class<T> type) {
        try {
            return record.get(columnName, type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String[] getColumnNames() {
        return Arrays.stream(record.fields())
            .map(field -> field.getName())
            .toArray(String[]::new);
    }

    @Override
    public boolean hasColumn(String columnName) {
        try {
            record.field(columnName);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
