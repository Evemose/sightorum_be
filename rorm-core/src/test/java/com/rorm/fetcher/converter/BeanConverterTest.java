package com.rorm.fetcher.converter;

import com.rorm.fetcher.Row;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class BeanConverterTest {

    @Test
    void shouldConvertRowToBean() {
        // Given
        var row = createTestRow();
        var converter = new BeanConverter<>(PersonBean.class);
        var consumedColumns = new HashSet<String>();

        // When
        var result = converter.convert(row, consumedColumns);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("John Doe");
        assertThat(result.getAge()).isEqualTo(30);
        assertThat(consumedColumns).containsExactlyInAnyOrder("id", "name", "age");
    }

    private Row createTestRow() {
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
        var converter = new BeanConverter<>(UserBean.class);
        var consumedColumns = new HashSet<String>();

        // When
        var result = converter.convert(row, consumedColumns);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(42L);
        assertThat(result.getFirstName()).isEqualTo("Jane");
        assertThat(result.getLastName()).isEqualTo("Smith");
        assertThat(consumedColumns).containsExactlyInAnyOrder("user_id", "first_name", "last_name");
    }

    private Row createSnakeCaseRow() {
        return new Row() {
            @Override
            public <T> T get(String columnName, Class<T> type) {
                var value = get(columnName);
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
        var converter = new BeanConverter<>(PersonBean.class);
        var consumedColumns = new HashSet<String>();

        // When
        var result = converter.convert(row, consumedColumns);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isNull();
        assertThat(result.getAge()).isNull();
        assertThat(consumedColumns).containsExactlyInAnyOrder("id", "name", "age");
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
        var converter = new BeanConverter<>(PersonBean.class);
        var consumedColumns = new HashSet<String>();

        // When
        var result = converter.convert(row, consumedColumns);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("John Doe");
        assertThat(result.getAge()).isEqualTo(30);
        // Note: "extra" column should NOT be consumed since there's no property for it
        assertThat(consumedColumns).containsExactlyInAnyOrder("id", "name", "age");
        assertThat(consumedColumns).doesNotContain("extra");
    }

    private Row createRowWithExtraColumns() {
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

    // Test bean
    public static class PersonBean {
        private Long id;
        private String name;
        private Integer age;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }

    public static class UserBean {
        private Long userId;
        private String firstName;
        private String lastName;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }
    }
}
