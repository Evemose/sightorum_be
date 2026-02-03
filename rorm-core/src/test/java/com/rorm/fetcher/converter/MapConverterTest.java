package com.rorm.fetcher.converter;

import com.rorm.fetcher.Row;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class MapConverterTest {

    @Test
    void shouldConvertRowToMap() {
        // Given
        var row = createTestRow();
        var converter = new MapConverter();
        var consumedColumns = new HashSet<String>();

        // When
        var result = converter.convert(row, consumedColumns);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsEntry("id", 1L);
        assertThat(result).containsEntry("name", "John Doe");
        assertThat(result).containsEntry("age", 30);
        assertThat(consumedColumns).containsExactlyInAnyOrder("id", "name", "age");
    }

    private Row createTestRow() {
        return new Row() {
            @Override
            public <T> T get(String columnName, Class<T> type) {
                Object value = get(columnName);
                return value != null ? type.cast(value) : null;
            }

            @Override
            public Object get(String columnName) {
                return switch (columnName) {
                    case "id" -> 1L;
                    case "name" -> "John Doe";
                    case "age" -> 30;
                    default -> null;
                };
            }

            @Override
            public String[] getColumnNames() {
                return new String[]{"id", "name", "age"};
            }

            @Override
            public boolean hasColumn(String columnName) {
                return columnName.equals("id") || columnName.equals("name") || columnName.equals("age");
            }
        };
    }

    @Test
    void shouldHandleNullValues() {
        // Given
        var row = createRowWithNulls();
        var converter = new MapConverter();
        var consumedColumns = new HashSet<String>();

        // When
        var result = converter.convert(row, consumedColumns);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsEntry("id", 1L);
        assertThat(result).containsKey("name");
        assertThat(result.get("name")).isNull();
        assertThat(consumedColumns).containsExactlyInAnyOrder("id", "name");
    }

    private Row createRowWithNulls() {
        return new Row() {
            @Override
            public <T> T get(String columnName, Class<T> type) {
                var value = get(columnName);
                return value != null ? type.cast(value) : null;
            }

            @Override
            public Object get(String columnName) {
                return switch (columnName) {
                    case "id" -> 1L;
                    case "name" -> null;
                    default -> null;
                };
            }

            @Override
            public String[] getColumnNames() {
                return new String[]{"id", "name"};
            }

            @Override
            public boolean hasColumn(String columnName) {
                return columnName.equals("id") || columnName.equals("name");
            }
        };
    }
}
