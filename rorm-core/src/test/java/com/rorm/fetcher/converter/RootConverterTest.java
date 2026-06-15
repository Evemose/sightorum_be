package com.rorm.fetcher.converter;

import com.rorm.fetcher.Row;
import com.rorm.metamodel.AttributeLocation;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.CompositeAttribute;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.IdDescriptor;
import com.rorm.metamodel.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Root-aware converters de-flatten a single result row into nested maps/records/beans using the metamodel's
 * composite attributes. A fake {@link Row} stands in for a JDBC result so the conversion is tested without a database.
 */
@DisplayName("Root-aware row converters")
class RootConverterTest {

    private final Root person = new Root("person", List.of(
        new BasicAttribute("id", new AttributeLocation("person", "id"), new DataType.NumericType(19, 0)),
        new BasicAttribute("name", new AttributeLocation("person", "name"), new DataType.StringType()),
        new CompositeAttribute("address", Set.of(
            new BasicAttribute("zip", new AttributeLocation("person", "zip"), new DataType.StringType()),
            new BasicAttribute("city", new AttributeLocation("person", "city"), new DataType.StringType())))
    ), IdDescriptor.longId("person"));

    @Test
    @DisplayName("map converter de-flattens composite columns into a nested map")
    void mapConverterNestsComposites() {
        var consumed = new HashSet<String>();
        var result = new RootMapConverter(person).convert(fullRow(), consumed);

        assertThat(result).containsEntry("id", 1L).containsEntry("name", "Alice");
        assertThat(result).extractingByKey("address").isEqualTo(Map.of("zip", "12345", "city", "NYC"));
        assertThat(consumed).containsExactlyInAnyOrder("id", "name", "zip", "city");
    }

    @Test
    @DisplayName("map converter omits a composite whose columns are all absent")
    void mapConverterOmitsAbsentComposite() {
        var result = new RootMapConverter(person).convert(idOnlyRow(), new HashSet<>());
        assertThat(result).containsOnlyKeys("id");
    }

    @Test
    @DisplayName("record converter instantiates a nested record")
    void recordConverterNestsComposites() {
        var consumed = new HashSet<String>();
        var result = new RootRecordConverter<>(PersonRecord.class, person).convert(fullRow(), consumed);

        assertThat(result).isEqualTo(new PersonRecord(1L, "Alice", new AddressRecord("12345", "NYC")));
        assertThat(consumed).containsExactlyInAnyOrder("id", "name", "zip", "city");
    }

    @Test
    @DisplayName("record converter leaves a composite null when its columns are absent")
    void recordConverterNullComposite() {
        var result = new RootRecordConverter<>(PersonRecord.class, person).convert(idOnlyRow(), new HashSet<>());
        assertThat(result).isEqualTo(new PersonRecord(1L, null, null));
    }

    @Test
    @DisplayName("record converter rejects a non-record type")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void recordConverterRejectsNonRecord() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RootRecordConverter(PersonBean.class, person))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a Java record");
    }

    @Test
    @DisplayName("bean converter populates a nested bean property")
    void beanConverterNestsComposites() {
        var consumed = new HashSet<String>();
        var result = new RootBeanConverter<>(PersonBean.class, person).convert(fullRow(), consumed);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Alice");
        assertThat(result.getAddress()).isNotNull();
        assertThat(result.getAddress().getZip()).isEqualTo("12345");
        assertThat(consumed).containsExactlyInAnyOrder("id", "name", "zip", "city");
    }

    private Row fullRow() {
        return rowOf(Map.of("id", 1L, "name", "Alice", "zip", "12345", "city", "NYC"));
    }

    private Row idOnlyRow() {
        return rowOf(Map.of("id", 1L));
    }

    private static Row rowOf(Map<String, Object> values) {
        return new Row() {
            @Override
            public Object get(String columnName) {
                return values.get(columnName);
            }

            @Override
            public <T> T get(String columnName, Class<T> type) {
                var value = values.get(columnName);
                return value == null ? null : type.cast(value);
            }

            @Override
            public String[] getColumnNames() {
                return values.keySet().toArray(String[]::new);
            }

            @Override
            public boolean hasColumn(String columnName) {
                return values.containsKey(columnName);
            }
        };
    }

    public record AddressRecord(String zip, String city) {
    }

    public record PersonRecord(Long id, String name, AddressRecord address) {
    }

    public static class AddressBean {
        private String zip;
        private String city;

        public String getZip() {
            return zip;
        }

        public void setZip(String zip) {
            this.zip = zip;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }

    public static class PersonBean {
        private Long id;
        private String name;
        private AddressBean address;

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

        public AddressBean getAddress() {
            return address;
        }

        public void setAddress(AddressBean address) {
            this.address = address;
        }
    }
}
