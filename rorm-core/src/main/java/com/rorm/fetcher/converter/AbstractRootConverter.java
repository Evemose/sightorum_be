package com.rorm.fetcher.converter;

import com.rorm.fetcher.Row;
import com.rorm.metamodel.*;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Base class for Root-aware converters with shared attribute extraction logic.
 */
public abstract class AbstractRootConverter {

    protected final Root root;

    protected AbstractRootConverter(Root root) {
        this.root = root;
    }

    @Nullable
    protected Object extractAttribute(Attribute attr, Row row, Set<String> consumed) {
        return switch (attr) {
            case BasicAttribute basic -> extractBasic(basic, row, consumed);
            case CompositeAttribute composite -> extractComposite(composite, row, consumed);
            case SingularReferenceAttribute _ -> null; // Can't hydrate from single row
            default -> null;
        };
    }

    @Nullable
    protected Object extractBasic(BasicAttribute attr, Row row, Set<String> consumed) {
        var column = attr.location().column();
        if (!row.hasColumn(column)) {
            return null;
        }
        consumed.add(column);
        return row.get(column);
    }

    @Nullable
    protected abstract Object extractComposite(CompositeAttribute attr, Row row, Set<String> consumed);

    protected boolean hasAnyColumn(CompositeAttribute composite, Row row) {
        for (var attr : composite.attributes()) {
            var found = switch (attr) {
                case BasicAttribute basic -> row.hasColumn(basic.location().column());
                case CompositeAttribute nested -> hasAnyColumn(nested, row);
                default -> false;
            };
            if (found) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    protected String findFirstColumn(Attribute.SingularAttribute attr) {
        return switch (attr) {
            case BasicAttribute basic -> basic.location().column();
            case CompositeAttribute composite -> {
                for (var nested : composite.attributes()) {
                    if (nested instanceof Attribute.SingularAttribute singular) {
                        var col = findFirstColumn(singular);
                        if (col != null) {
                            yield col;
                        }
                    }
                }
                yield null;
            }
            default -> null;
        };
    }

    protected boolean isProjected(Attribute.SingularAttribute attr, Row row) {
        var col = findFirstColumn(attr);
        return col != null && row.hasColumn(col);
    }
}
