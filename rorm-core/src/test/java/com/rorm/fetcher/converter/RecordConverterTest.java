package com.rorm.fetcher.converter;

import com.rorm.fetcher.Row;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class RecordConverterTest {

    @Test
    void shouldConvertRowToRecord() {
        // Given
        var row = createTestRow();
        var converter = new RecordConverter<>(Person.class);
        var consumedColumns = new HashSet<String>();

        // When
        Person result = converter.convert(row, consumedColumns);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("John Doe");
        assertThat(result.age()).isEqualTo(30);
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
    void shouldHandleSnakeCaseColumns() {
        // Given
        var row = createSnakeCaseRow();
        var converter = new RecordConverter<>(User.class);
        var consumedColumns = new HashSet<String>();

        // When
        User result = converter.convert(row, consumedColumns);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo(42L);
        assertThat(result.firstName()).isEqualTo("Jane");
        assertThat(result.lastName()).isEqualTo("Smith");
        assertThat(consumedColumns).containsExactlyInAnyOrder("user_id", "first_name", "last_name");
    }

    private Row createSnakeCaseRow() {
        return new Row() {
            @Override
            public <T> T get(String columnName, Class<T> type) {
                Object value = get(columnName);
                return value != null ? type.cast(value) : null;
            }

            @Override
            public Object get(String columnName) {
                return switch (columnName) {
                    case "user_id" -> 42L;
                    case "first_name" -> "Jane";
                    case "last_name" -> "Smith";
                    default -> null;
                };
            }

            @Override
            public String[] getColumnNames() {
                return new String[]{"user_id", "first_name", "last_name"};
            }

            @Override
            public boolean hasColumn(String columnName) {
                return columnName.equals("user_id") || columnName.equals("first_name") || columnName.equals("last_name");
            }
        };
    }

    @Test
    void shouldHandleNullValues() {
        // Given
        var row = createRowWithNulls();
        var converter = new RecordConverter<>(Person.class);
        var consumedColumns = new HashSet<String>();

        // When
        var result = converter.convert(row, consumedColumns);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isNull();
        assertThat(result.age()).isNull();
        assertThat(consumedColumns).containsExactlyInAnyOrder("id", "name", "age");
    }

    private Row createRowWithNulls() {
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
                    case "name", "age" -> null;
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
    void shouldWarnAboutDanglingColumns() {
        // Given
        var row = createRowWithExtraColumns();
        var converter = new RecordConverter<>(Person.class);
        var consumedColumns = new HashSet<String>();

        // When
        var result = converter.convert(row, consumedColumns);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("John Doe");
        assertThat(result.age()).isEqualTo(30);
        // Note: "extra" column should NOT be consumed
        assertThat(consumedColumns).containsExactlyInAnyOrder("id", "name", "age");
        assertThat(consumedColumns).doesNotContain("extra");
    }

    private Row createRowWithExtraColumns() {
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
                    case "extra" -> "not needed";
                    default -> null;
                };
            }

            @Override
            public String[] getColumnNames() {
                return new String[]{"id", "name", "age", "extra"};
            }

            @Override
            public boolean hasColumn(String columnName) {
                return columnName.equals("id") || columnName.equals("name") || columnName.equals("age") || columnName.equals("extra");
            }
        };
    }

    // Test record
    record Person(Long id, String name, Integer age) {}

    record User(Long userId, String firstName, String lastName) {}
}
