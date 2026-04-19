package com.rorm.engine;

import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.ReferenceAttribute;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.metamodel.ReferenceAttribute.SameTableColumn;
import com.rorm.metamodel.SingularReferenceAttribute;
import com.rorm.query.Path;

final class PathUtils {

    private PathUtils() {
    }

    static boolean isDirectReferenceId(Path path) {
        if (!(path.target() instanceof BasicAttribute attr)) {
            return false;
        }
        if (path.parent() == null) {
            return false;
        }
        if (!(path.parent().target() instanceof SingularReferenceAttribute singularRef)) {
            return false;
        }
        if (!singularRef.targetRoot().idDescriptor().idAttribute().equals(attr)) {
            return false;
        }
        return switch (singularRef.mappingStrategy()) {
            case SameTableColumn _, JoinTableMapping _ -> true;
            case InverseRootTableColumn _ -> false;
        };
    }

    static Path extendReferenceIfNeeded(Path path) {
        if (path.target() instanceof ReferenceAttribute refAttr) {
            return new Path(refAttr.targetRoot().idDescriptor().idAttribute(), path);
        }
        return path;
    }
}
