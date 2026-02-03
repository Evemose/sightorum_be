package com.rorm.fetcher.converter;

import com.rorm.fetcher.Row;
import com.rorm.fetcher.RowConverter;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Set;

/**
 * Converts a Row to a Java record using its canonical constructor.
 * Supports both camelCase and snake_case column name mapping.
 */
public class RecordConverter<T extends Record> implements RowConverter<T> {

    private final Class<T> recordType;
    private final Constructor<T> constructor;

    public RecordConverter(Class<T> recordType) {
        if (!recordType.isRecord()) {
            throw new IllegalArgumentException(recordType.getName() + " is not a Java record type");
        }
        this.recordType = recordType;
        this.constructor = findCanonicalConstructor();
    }

    private Constructor<T> findCanonicalConstructor() {
        var components = recordType.getRecordComponents();
        if (components == null || components.length == 0) {
            throw new IllegalArgumentException("Record " + recordType.getName() + " has no components");
        }

        var paramTypes = Arrays.stream(components)
            .map(c -> c.getType())
            .toArray(Class[]::new);

        try {
            return recordType.getDeclaredConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Cannot find canonical constructor for " + recordType.getName(), e);
        }
    }

    @Override
    public T convert(Row row, Set<String> consumed) {
        var params = constructor.getParameters();
        var args = new Object[params.length];

        for (var i = 0; i < params.length; i++) {
            var param = params[i];
            var name = param.getName();
            var snakeName = toSnakeCase(name);

            if (row.hasColumn(name)) {
                args[i] = row.get(name, param.getType());
                consumed.add(name);
            } else if (row.hasColumn(snakeName)) {
                args[i] = row.get(snakeName, param.getType());
                consumed.add(snakeName);
            }
        }

        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new RowConversionException("Failed to instantiate record " + recordType.getName(), e);
        }
    }

    private String toSnakeCase(String camelCase) {
        var result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            var c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
