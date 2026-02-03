package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.metamodel.ReferenceAttribute.SameTableColumn;
import com.rorm.query.Path;
import lombok.Getter;
import org.jooq.Field;
import org.jooq.Table;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.jooq.impl.DSL.*;

final class QueryContext {

    private final Map<Path, JoinInfo> joinRegistry = new HashMap<>();
    private final Map<String, JoinedRootInfo> aliasRegistry = new HashMap<>();
    private final Table<?> rootTable;
    private final Root root;
    private final @Nullable QueryContext parent;
    private final @Nullable String schema;
    private final int depth;
    private int aliasCounter = 0;

    QueryContext(Root root) {
        this(AliasedRoot.of(root));
    }

    QueryContext(AliasedRoot from) {
        this(from, null, null, 0);
    }

    QueryContext(AliasedRoot from, @Nullable String schema, @Nullable QueryContext parent, int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("Depth cannot be negative");
        }
        this.root = from.root();
        this.depth = depth;
        this.parent = parent;
        this.schema = schema;
        this.rootTable = tableWithSchema(from.root().primaryTableName()).as(generateAlias());

        // Register the from alias
        var info = new JoinedRootInfo(from, rootTable);
        aliasRegistry.put(from.alias(), info);
    }

    private Table<?> tableWithSchema(String tableName) {
        return schema != null ? table(name(schema, tableName)) : table(name(tableName));
    }

    QueryContext(AliasedRoot from, @Nullable String schema) {
        this(from, schema, null, 0);
    }

    private String generateAlias() {
        return "t" + depth + "_" + aliasCounter++;
    }

    QueryContext nested(AliasedRoot root) {
        return new QueryContext(root, schema, this, depth + 1);
    }

    QueryContext nested(Root root) {
        return new QueryContext(AliasedRoot.of(root), schema, this, depth + 1);
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
            // Check if this is a JoinedRoot at the base
            if (path.target() instanceof AliasedRoot aliasedRoot) {
                var info = getOrRegisterJoinedRoot(aliasedRoot);
                return new JoinInfo(info.table(), aliasedRoot.root().primaryTableName(), null, null);
            }
            return new JoinInfo(rootTable, root.primaryTableName(), null, null);
        }

        var parentJoinInfo = resolveJoin(path.parent());
        var parentTarget = path.parent().target();

        return switch (parentTarget) {
            case AliasedRoot aliasedRoot -> {
                // JoinedRoot acts as a new root context for the path
                var info = getOrRegisterJoinedRoot(aliasedRoot);
                yield new JoinInfo(info.table(), aliasedRoot.root().primaryTableName(), null, null);
            }
            case SingularReferenceAttribute ref -> handleSingularReference(parentJoinInfo, ref);
            case PluralReferenceAttribute ref -> handlePluralReference(parentJoinInfo, ref);
            case CollectionAttribute col -> handleCollection(parentJoinInfo, col);
            case CompositeAttribute _, CompositeElement _, BasicAttribute _, BasicElement _ -> parentJoinInfo;
        };
    }

    private JoinInfo handleSingularReference(JoinInfo parent, SingularReferenceAttribute ref) {
        var targetTable = ref.targetRoot().primaryTableName();
        var joined = tableWithSchema(targetTable).as(generateAlias());

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

    /**
     * Gets or registers a JoinedRoot.
     * If already registered, returns the existing info; otherwise registers it.
     *
     * @param aliasedRoot the joined root to get or register
     * @return the JoinedRootInfo for the joined root
     */
    JoinedRootInfo getOrRegisterJoinedRoot(AliasedRoot aliasedRoot) {
        var existing = aliasRegistry.get(aliasedRoot.alias());
        if (existing != null) {
            if (!existing.aliasedRoot().root().equals(aliasedRoot.root())) {
                throw new DuplicateAliasException(aliasedRoot.alias(),
                    existing.aliasedRoot().root().primaryTableName(),
                    aliasedRoot.root().primaryTableName());
            }
            return existing;
        }
        return registerJoinedRoot(aliasedRoot);
    }

    /**
     * Registers a JoinedRoot with its alias.
     * If the alias is already registered, throws an exception.
     *
     * @param aliasedRoot the joined root to register
     * @return the JoinInfo for the registered joined root
     * @throws DuplicateAliasException if the alias is already registered
     */
    JoinedRootInfo registerJoinedRoot(AliasedRoot aliasedRoot) {
        var alias = aliasedRoot.alias();
        var existing = aliasRegistry.get(alias);
        if (existing != null) {
            throw new DuplicateAliasException(alias, existing.aliasedRoot().root().primaryTableName(),
                aliasedRoot.root().primaryTableName());
        }

        var targetTable = aliasedRoot.root().primaryTableName();
        var aliasedTable = table(name(targetTable)).as(generateAlias());
        var info = new JoinedRootInfo(aliasedRoot, aliasedTable);
        aliasRegistry.put(alias, info);
        return info;
    }

    record JoinInfo(Table<?> table, String actualTableName, Field<?> leftJoinColumn, Field<?> rightJoinColumn) {}

    record JoinedRootInfo(AliasedRoot aliasedRoot, Table<?> table) {}

    /**
     * Exception thrown when a duplicate alias is detected during registration.
     */
    @Getter
    static class DuplicateAliasException extends RuntimeException {
        private final String alias;
        private final String existingRootTable;
        private final String newRootTable;

        DuplicateAliasException(String alias, String existingRootTable, String newRootTable) {
            super("Duplicate alias '%s' detected: already used for table '%s', cannot be used for table '%s'"
                .formatted(alias, existingRootTable, newRootTable));
            this.alias = alias;
            this.existingRootTable = existingRootTable;
            this.newRootTable = newRootTable;
        }

    }
}
