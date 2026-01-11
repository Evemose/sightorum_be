package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.metamodel.ReferenceAttribute.SameTableColumn;
import com.rorm.query.Path;
import org.jooq.Field;
import org.jooq.Table;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.jooq.impl.DSL.*;

final class QueryContext {

    private final Map<Path, JoinInfo> joinRegistry = new HashMap<>();
    private final Table<?> rootTable;
    private final Root root;
    private final @Nullable QueryContext parent;
    private final int depth;
    private int aliasCounter = 0;

    QueryContext(Root root) {
        this(root, null, 0);
    }

    QueryContext(Root root, @Nullable QueryContext parent, int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("Depth cannot be negative");
        }
        this.root = root;
        this.depth = depth;
        this.parent = parent;
        this.rootTable = table(name(root.primaryTableName())).as(generateAlias());
    }

    QueryContext nested(Root root) {
        return new QueryContext(root, this, depth + 1);
    }

    QueryContext ancestor(int levels) {
        if (levels == 0) {
            return this;
        }
        if (parent == null) {
            throw new IllegalStateException("Cannot access outer scope at depth " + levels + " - no parent context");
        }
        return parent.ancestor(levels - 1);
    }

    Table<?> rootTable() {
        return rootTable;
    }

    JoinInfo resolveJoin(Path path) {
        var existing = joinRegistry.get(path);
        if (existing != null) {
            return existing;
        }
        var created = createJoin(path);
        joinRegistry.put(path, created);
        return created;
    }

    Field<?> resolveField(BasicAttribute attr, Table<?> table) {
        return field(name(table.getName(), attr.location().column()));
    }

    private JoinInfo createJoin(Path path) {
        if (path.parent() == null) {
            return new JoinInfo(rootTable, root.primaryTableName(), null, null);
        }

        var parentJoinInfo = resolveJoin(path.parent());
        var parentTarget = path.parent().target();

        return switch (parentTarget) {
            case SingularReferenceAttribute ref -> handleSingularReference(parentJoinInfo, ref);
            case PluralReferenceAttribute ref -> handlePluralReference(parentJoinInfo, ref);
            case CollectionAttribute col -> handleCollection(parentJoinInfo, col);
            case CompositeAttribute _, CompositeElement _, BasicAttribute _, BasicElement _ -> parentJoinInfo;
        };
    }

    private JoinInfo handleSingularReference(JoinInfo parent, SingularReferenceAttribute ref) {
        var targetTable = ref.targetRoot().primaryTableName();
        var joined = table(name(targetTable)).as(generateAlias());

        return switch (ref.mappingStrategy()) {
            case JoinTableMapping jtm -> new JoinInfo(joined, targetTable,
                field(name(parent.table().getName(), jtm.joinColumnLocation().column())),
                field(name(joined.getName(), jtm.inverseJoinColumnName())));
            case InverseRootTableColumn inv -> new JoinInfo(joined, targetTable,
                field(name(parent.table().getName(), Objects.requireNonNullElse(this.parent, this).root.idDescriptor().columnName())),
                field(name(joined.getName(), inv.columnName())));
            case SameTableColumn stc -> new JoinInfo(joined, targetTable,
                field(name(parent.table().getName(), stc.columnName())),
                field(name(joined.getName(), ref.targetRoot().idDescriptor().columnName())));
        };
    }

    private JoinInfo handlePluralReference(JoinInfo parent, PluralReferenceAttribute ref) {
        var targetTable = ref.targetRoot().primaryTableName();

        return switch (ref.mappingStrategy()) {
            case InverseRootTableColumn inv -> {
                var joined = table(name(targetTable)).as(generateAlias());
                yield new JoinInfo(joined, targetTable,
                    field(name(parent.table().getName(), Objects.requireNonNullElse(this.parent, this).root.idDescriptor().columnName())),
                    field(name(joined.getName(), inv.columnName())));
            }
            case JoinTableMapping jtm -> {
                var joinTableName = jtm.joinColumnLocation().table();
                var joined = table(name(joinTableName)).as(generateAlias());
                yield new JoinInfo(joined, joinTableName,
                    field(name(parent.table().getName(), Objects.requireNonNull(this.parent).root.idDescriptor().columnName())),
                    field(name(joined.getName(), jtm.joinColumnLocation().column())));
            }
            case SameTableColumn stc -> {
                var joined = table(name(targetTable)).as(generateAlias());
                yield new JoinInfo(joined, targetTable,
                    field(name(parent.table().getName(), stc.columnName())),
                    field(name(joined.getName(), ref.targetRoot().idDescriptor().columnName())));
            }
        };
    }

    private JoinInfo handleCollection(JoinInfo parent, CollectionAttribute col) {
        var tableName = col.tableName();
        if (tableName == null) {
            return parent;
        }

        var joined = table(name(tableName)).as(generateAlias());
        var underscoreIdx = tableName.lastIndexOf('_');
        var ownerSingular = underscoreIdx > 0 ? tableName.substring(0, underscoreIdx) : tableName;
        var fkColumn = ownerSingular + "_id";

        return new JoinInfo(joined, tableName,
            field(name(parent.table().getName(), "id")),
            field(name(joined.getName(), fkColumn)));
    }

    private String generateAlias() {
        return "t" + depth + "_" + aliasCounter++;
    }

    record JoinInfo(Table<?> table, String actualTableName, Field<?> leftJoinColumn, Field<?> rightJoinColumn) {}
}
