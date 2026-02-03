package com.rorm.fetcher.converter;

import com.rorm.fetcher.Row;
import com.rorm.fetcher.RowConverter;
import com.rorm.metamodel.Attribute;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.CompositeAttribute;
import com.rorm.metamodel.Root;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;

/**
 * Converts a Row to a Java record using Root schema metadata.
 * Supports de-flattening of arbitrarily nested composite attributes.
 */
public class RootRecordConverter<T extends Record> extends AbstractRootConverter implements RowConverter<T> {

    private final Class<T> recordType;

    public RootRecordConverter(Class<T> recordType, Root root) {
        super(root);
        if (!recordType.isRecord()) {
            throw new IllegalArgumentException(recordType.getName() + " is not a Java record type");
        }
        this.recordType = recordType;
    }

    @Override
    public T convert(Row row, Set<String> consumed) {
        return instantiateRecord(recordType, root.attributes(), row, consumed);
    }

    @Override
    protected Object extractComposite(CompositeAttribute composite, Row row, Set<String> consumed) {
        // Not used directly - we handle composites in instantiateRecord
        return null;
    }

    private <R> R instantiateRecord(Class<R> type, Collection<? extends Attribute> attributes, Row row, Set<String> consumed) {
        var constructor = findCanonicalConstructor(type);

        var attrMap = new HashMap<String, Attribute>();
        for (var attr : attributes) {
            attrMap.put(attr.name(), attr);
        }

        var params = constructor.getParameters();
        var args = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            var param = params[i];
            var attr = attrMap.get(param.getName());

            args[i] = switch (attr) {
                case BasicAttribute basic -> extractBasic(basic, row, consumed);
                case CompositeAttribute composite -> {
                    if (!hasAnyColumn(composite, row)) {
                        yield null;
                    }
                    if (param.getType().isRecord()) {
                        yield instantiateRecord(param.getType(), composite.attributes(), row, consumed);
                    }
                    throw new RowConversionException("Composite attribute " + param.getName() +
                                                     " requires a record type, got " + param.getType().getName());
                }
                case null, default -> null;
            };
        }

        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new RowConversionException("Failed to instantiate record " + type.getName(), e);
        }
    }

    private <R> Constructor<R> findCanonicalConstructor(Class<R> type) {
        var components = type.getRecordComponents();
        if (components == null || components.length == 0) {
            throw new IllegalArgumentException("Record " + type.getName() + " has no components");
        }

        var paramTypes = Arrays.stream(components)
            .map(RecordComponent::getType)
            .toArray(Class[]::new);

        try {
            return type.getDeclaredConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Cannot find canonical constructor for " + type.getName(), e);
        }
    }
}
