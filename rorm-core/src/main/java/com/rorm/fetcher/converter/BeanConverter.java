package com.rorm.fetcher.converter;

import com.rorm.fetcher.Row;
import com.rorm.fetcher.RowConverter;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.util.Set;

/**
 * Converts a Row to a Java bean using Spring's BeanWrapper.
 * Supports both camelCase and snake_case column name mapping.
 */
public class BeanConverter<T> implements RowConverter<T> {

    private final Class<T> beanType;

    public BeanConverter(Class<T> beanType) {
        this.beanType = beanType;
    }

    @Override
    public T convert(Row row, Set<String> consumed) {
        try {
            var instance = beanType.getDeclaredConstructor().newInstance();
            var wrapper = new BeanWrapperImpl(instance);

            for (var column : row.getColumnNames()) {
                var value = row.get(column);

                if (trySet(wrapper, column, value) || trySet(wrapper, toCamelCase(column), value)) {
                    consumed.add(column);
                }
            }

            return instance;
        } catch (ReflectiveOperationException e) {
            throw new RowConversionException("Failed to instantiate bean " + beanType.getName(), e);
        }
    }

    private boolean trySet(BeanWrapper wrapper, String property, Object value) {
        if (!wrapper.isWritableProperty(property)) {
            return false;
        }
        wrapper.setPropertyValue(property, value);
        return true;
    }

    private String toCamelCase(String snake) {
        var result = new StringBuilder();
        var upper = false;

        for (var c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
                upper = false;
            }
        }

        return result.toString();
    }
}
