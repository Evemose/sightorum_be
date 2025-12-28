package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.query.Path;
import org.jooq.Field;
import org.jooq.Table;

import java.util.HashMap;
import java.util.Map;

import static org.jooq.impl.DSL.*;

final class QueryContext {

    private final Map<Path, JoinInfo> joinRegistry = new HashMap<>();
    private final Table<?> rootTable;
    private final String rootTableName;
    private int aliasCounter = 0;

    QueryContext(Root root) {
        this.rootTableName = root.primaryTableName();
        this.rootTable = table(name(rootTableName)).as("t0");
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
            return new JoinInfo(rootTable, rootTableName, null, null);
        }

        var parentJoinInfo = resolveJoin(path.parent());
        var parentTarget = path.parent().target();

        return switch (parentTarget) {
            case SingularReferenceAttribute ref -> handleSingularReference(parentJoinInfo, ref);
            case PluralReferenceAttribute ref -> handlePluralReference(parentJoinInfo, ref);
            case CollectionAttribute col -> handleCollection(parentJoinInfo, col);
            case CompositeAttribute _, CompositeElement _ -> parentJoinInfo;
            case BasicAttribute _, BasicElement _ -> parentJoinInfo;
        };
    }

    private JoinInfo handleSingularReference(JoinInfo parent, SingularReferenceAttribute ref) {
        var targetTable = ref.targetRoot().primaryTableName();
        var joined = table(name(targetTable)).as("t" + ++aliasCounter);

        return switch (ref.mappingStrategy()) {
            case JoinTableMapping jtm -> new JoinInfo(joined, targetTable,
                field(name(parent.table().getName(), jtm.joinColumnLocation().column())),
                field(name(joined.getName(), jtm.inverseJoinColumnName())));
            case InverseRootTableColumn inv -> new JoinInfo(joined, targetTable,
                field(name(parent.table().getName(), "id")),
                field(name(joined.getName(), inv.columnName())));
        };
    }

    private JoinInfo handlePluralReference(JoinInfo parent, PluralReferenceAttribute ref) {
        var targetTable = ref.targetRoot().primaryTableName();

        return switch (ref.mappingStrategy()) {
            case InverseRootTableColumn inv -> {
                var joined = table(name(targetTable)).as("t" + ++aliasCounter);
                yield new JoinInfo(joined, targetTable,
                    field(name(parent.table().getName(), "id")),
                    field(name(joined.getName(), inv.columnName())));
            }
            case JoinTableMapping jtm -> {
                var joinTableName = jtm.joinColumnLocation().table();
                var joined = table(name(joinTableName)).as("t" + ++aliasCounter);
                yield new JoinInfo(joined, joinTableName,
                    field(name(parent.table().getName(), "id")),
                    field(name(joined.getName(), jtm.joinColumnLocation().column())));
            }
        };
    }

    private JoinInfo handleCollection(JoinInfo parent, CollectionAttribute col) {
        var tableName = col.tableName();
        if (tableName == null) {
            return parent;
        }

        var joined = table(name(tableName)).as("t" + ++aliasCounter);
        var underscoreIdx = tableName.lastIndexOf('_');
        var ownerSingular = underscoreIdx > 0 ? tableName.substring(0, underscoreIdx) : tableName;
        var fkColumn = ownerSingular + "_id";

        return new JoinInfo(joined, tableName,
            field(name(parent.table().getName(), "id")),
            field(name(joined.getName(), fkColumn)));
    }

    record JoinInfo(Table<?> table, String actualTableName, Field<?> leftJoinColumn, Field<?> rightJoinColumn) {}
}
