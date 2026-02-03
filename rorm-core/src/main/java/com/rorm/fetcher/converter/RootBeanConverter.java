package com.rorm.fetcher.converter;

import com.rorm.fetcher.Row;
import com.rorm.fetcher.RowConverter;
import com.rorm.metamodel.Attribute;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.CompositeAttribute;
import com.rorm.metamodel.Root;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.util.Set;

/**
 * Converts a Row to a Java bean using Root schema metadata.
 * Supports de-flattening of arbitrarily nested composite attributes.
 */
public class RootBeanConverter<T> extends AbstractRootConverter implements RowConverter<T> {

    private final Class<T> beanType;

    public RootBeanConverter(Class<T> beanType, Root root) {
        super(root);
        this.beanType = beanType;
    }

    @Override
    public T convert(Row row, Set<String> consumed) {
        try {
            var instance = beanType.getDeclaredConstructor().newInstance();
            var wrapper = new BeanWrapperImpl(instance);

            for (var attr : root.attributes()) {
                if (attr instanceof Attribute.SingularAttribute) {
                    populateProperty(attr, row, wrapper, consumed);
                }
            }

            return instance;
        } catch (ReflectiveOperationException e) {
            throw new RowConversionException("Failed to instantiate bean " + beanType.getName(), e);
        }
    }

    @Override
    protected Object extractComposite(CompositeAttribute composite, Row row, Set<String> consumed) {
        throw new UnsupportedOperationException("extractComposite is not used in RootBeanConverter");
    }

    private void populateProperty(Attribute attr, Row row, BeanWrapper wrapper, Set<String> consumed) {
        var name = attr.name();

        if (!wrapper.isWritableProperty(name)) {
            return;
        }

        switch (attr) {
            case BasicAttribute basic -> {
                var value = extractBasic(basic, row, consumed);
                if (value != null) {
                    wrapper.setPropertyValue(name, value);
                }
            }
            case CompositeAttribute composite -> {
                if (!hasAnyColumn(composite, row)) {
                    return;
                }
                var type = wrapper.getPropertyType(name);
                if (type == null) {
                    return;
                }
                try {
                    var nested = type.getDeclaredConstructor().newInstance();
                    var nestedWrapper = new BeanWrapperImpl(nested);

                    for (var nestedAttr : composite.attributes()) {
                        if (nestedAttr instanceof Attribute.SingularAttribute) {
                            populateProperty(nestedAttr, row, nestedWrapper, consumed);
                        }
                    }

                    wrapper.setPropertyValue(name, nested);
                } catch (ReflectiveOperationException e) {
                    throw new RowConversionException("Failed to instantiate composite " + type.getName(), e);
                }
            }
            default -> {
            }
        }
    }
}
