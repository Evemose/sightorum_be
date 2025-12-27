package com.rorm.engine;

import com.rorm.metamodel.Attribute.BasicAttribute;
import com.rorm.metamodel.Attribute.ReferenceAttribute;
import com.rorm.metamodel.Root;
import com.rorm.query.Path;
import org.jooq.Field;
import org.jooq.Table;

import java.util.HashMap;
import java.util.Map;

import static org.jooq.impl.DSL.*;

final class QueryContext {

    private final Map<Path, JoinInfo> joinRegistry = new HashMap<>();
    private final Table<?> rootTable;
    private int aliasCounter = 0;

    QueryContext(Root root) {
        this.rootTable = resolveRootTable(root);
    }

    private Table<?> resolveRootTable(Root root) {
        return table(name(root.primaryTableName())).as("t0");
    }

    Table<?> rootTable() {
        return rootTable;
    }

    JoinInfo resolveJoin(Path path) {
        var existing = joinRegistry.get(path);
        if (existing != null) {
            return existing;
        }
        var joinInfo = createJoin(path);
        joinRegistry.put(path, joinInfo);
        return joinInfo;
    }

    Field<?> resolveField(BasicAttribute attr, Table<?> table) {
        return field(name(table.getName(), attr.location().column()));
    }

    private JoinInfo createJoin(Path path) {
        if (path.parent() == null) {
            return new JoinInfo(rootTable, null, null);
        }

        var parentJoinInfo = resolveJoin(path.parent());
        var parentTarget = path.parent().target();

        if (parentTarget instanceof ReferenceAttribute refAttr) {
            var fkColumnName = refAttr.location().column();

            String targetTableName = null;
            var currentTarget = path.target();
            if (currentTarget instanceof BasicAttribute basicAttr) {
                targetTableName = basicAttr.location().table();
            } else if (currentTarget instanceof ReferenceAttribute currentRef) {
                targetTableName = currentRef.location().table();
            }

            if (targetTableName != null) {
                var joinedTable = table(name(targetTableName)).as("t" + ++aliasCounter);
                var leftJoinColumn = field(name(parentJoinInfo.table().getName(), fkColumnName));
                var rightJoinColumn = field(name(joinedTable.getName(), "id"));

                return new JoinInfo(joinedTable, leftJoinColumn, rightJoinColumn);
            }
        }

        return parentJoinInfo;
    }

    record JoinInfo(Table<?> table, Field<?> leftJoinColumn, Field<?> rightJoinColumn) {}
}
