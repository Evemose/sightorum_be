package com.rorm.client.data;

import com.rorm.client.data.dto.DatasetInfo;
import com.rorm.client.data.dto.QueryResponse;
import com.rorm.client.data.dto.SampleResponse;
import com.rorm.client.data.dto.TableProfileDTO;
import com.rorm.client.metamodel.MetamodelService;
import com.rorm.dataimport.pipeline.profile.SchemaProfile;
import com.rorm.dataimport.pipeline.profile.SchemaProfile.EntityProfile;
import com.rorm.dataimport.pipeline.profile.SchemaProfileStore;
import com.rorm.dto.QueryDTO;
import com.rorm.fetcher.Fetcher;
import com.rorm.mapper.QueryMapper;
import com.rorm.metamodel.AliasedRoot;
import com.rorm.metamodel.Attribute;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import com.rorm.query.Query;
import com.rorm.query.Selector.RootSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final MetamodelService metamodelService;
    private final QueryMapper queryMapper;
    private final Fetcher fetcher;
    private final DSLContext dsl;
    private final SchemaProfileStore profileStore;
    private final JpaSchemaProfileStore jpaProfileStore;

    public List<DatasetInfo> listDatasets() {
        // List view stays cheap on a deployed DB with 100+ schemas. Two
        // bulk reads cover the whole answer: one `findAll()` over
        // metamodels (loads every ModelSpace), one `findBySchemaNameIn`
        // over profiles. From there everything assembles in-process —
        // no per-schema SELECT, no `SELECT COUNT(*)` against the
        // deployed DB during a list call. The per-schema detail endpoint
        // still does the live fallback for accurate counts when the user
        // drills into a profile-less schema.
        var spaces = metamodelService.getAllModelSpaces();
        var profiles = jpaProfileStore.getAll(spaces.keySet());

        return spaces.entrySet().stream()
            .map(e -> buildSummary(e.getKey(), e.getValue(), profiles.get(e.getKey())))
            .toList();
    }

    public DatasetInfo getDatasetInfo(String schemaName) {
        var modelSpace = metamodelService.getModelSpace(schemaName);
        var profile = profileStore.get(schemaName).orElse(null);

        var tables = new ArrayList<DatasetInfo.TableInfo>();
        long totalRows = 0;

        for (var root : modelSpace.roots()) {
            var tableInfo = buildTableInfo(schemaName, root, profile);
            tables.add(tableInfo);
            totalRows += tableInfo.rowCount();
        }

        return new DatasetInfo(schemaName, tables, totalRows);
    }

    public Optional<TableProfileDTO> getTableProfile(String schemaName, String tableName) {
        return profileStore.get(schemaName)
            .flatMap(p -> p.findEntity(tableName))
            .map(e -> toTableProfile(schemaName, e));
    }

    private DatasetInfo buildSummary(String schemaName, ModelSpace modelSpace, @Nullable SchemaProfile profile) {
        var tables = new ArrayList<DatasetInfo.TableInfo>();
        long totalRows = 0;

        for (var root : modelSpace.roots()) {
            var tableName = root.primaryTableName();
            var columns = getColumnNames(root);
            var entityProfile = profile != null ? profile.findEntity(tableName).orElse(null) : null;
            if (entityProfile != null) {
                tables.add(new DatasetInfo.TableInfo(
                    tableName,
                    entityProfile.rowCount(),
                    columns,
                    toFlagNames(entityProfile.flags()),
                    "PROFILE"
                ));
                totalRows += entityProfile.rowCount();
            } else {
                tables.add(new DatasetInfo.TableInfo(
                    tableName, 0L, columns, Set.of(), "UNKNOWN"
                ));
            }
        }
        return new DatasetInfo(schemaName, tables, totalRows);
    }

    private DatasetInfo.TableInfo buildTableInfo(String schemaName, Root root, @Nullable SchemaProfile profile) {
        var tableName = root.primaryTableName();
        var columns = getColumnNames(root);
        var entityProfile = profile != null ? profile.findEntity(tableName).orElse(null) : null;

        if (entityProfile != null) {
            return new DatasetInfo.TableInfo(
                tableName,
                entityProfile.rowCount(),
                columns,
                toFlagNames(entityProfile.flags()),
                "PROFILE"
            );
        }
        return new DatasetInfo.TableInfo(tableName, getTableRowCount(schemaName, tableName), columns);
    }

    private TableProfileDTO toTableProfile(String schemaName, EntityProfile entity) {
        var attrs = entity.attributes().stream()
            .map(a -> new TableProfileDTO.AttributeProfileDTO(
                a.name(),
                a.dataTypeName(),
                a.category().name(),
                a.statistics()))
            .toList();
        return new TableProfileDTO(
            schemaName,
            entity.name(),
            entity.rowCount(),
            toFlagNames(entity.flags()),
            attrs);
    }

    private Set<String> toFlagNames(Set<SchemaProfile.SummaryFlag> flags) {
        return flags.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    private List<String> getColumnNames(Root root) {
        return root.attributes().stream()
            .filter(BasicAttribute.class::isInstance)
            .map(Attribute::name)
            .toList();
    }

    private long getTableRowCount(String schemaName, String tableName) {
        try {
            var sql = "SELECT COUNT(*) FROM %s.%s".formatted(schemaName, tableName);
            var result = dsl.fetchOne(sql);
            return result != null ? result.get(0, Long.class) : 0L;
        } catch (Exception e) {
            log.warn("Failed to get row count for {}.{}: {}", schemaName, tableName, e.getMessage());
            return 0L;
        }
    }

    public SampleResponse getSamples(String schemaName, String tableName, int limit) {
        var modelSpace = metamodelService.getModelSpace(schemaName);

        var root = modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals(tableName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Table not found: " + tableName));

        var columns = getColumnNames(root);
        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(RootSelector.ofDistinct(root))
            .limit((long) limit)
            .build();

        var results = fetcher.withSchema(schemaName, () ->
            fetcher.queryForRootAsMap(query, root));

        var totalRows = profileStore.get(schemaName)
            .flatMap(p -> p.findEntity(tableName))
            .map(EntityProfile::rowCount)
            .orElseGet(() -> getTableRowCount(schemaName, tableName));

        return new SampleResponse(schemaName, tableName, columns, results, totalRows);
    }

    public QueryResponse executeQuery(String schemaName, QueryDTO queryDTO) {
        var startTime = System.currentTimeMillis();

        try {
            var modelSpace = metamodelService.getModelSpace(schemaName);
            var query = queryMapper.toEntity(queryDTO, modelSpace);

            var effectiveQuery = applyLimitCap(query);

            // The fetcher reads the target schema from a `ScopedValue`
            // (see `JooqFetcher.CURRENT_SCHEMA`). Without `withSchema`
            // the call throws `ScopedValue not bound`; we wrap so the
            // jOOQ query transformer can qualify table names against
            // the right Postgres schema.
            //
            // `queryForRootAsMap` always projects to the root entity's
            // attribute shape (RootMapConverter), so SELECT lists that
            // pick specific expressions or aggregations come back with
            // empty cells. For non-RootSelector queries we use the
            // generic Map converter, which returns one row entry per
            // result column the SQL actually produced — preserving
            // aggregates and aliases.
            @SuppressWarnings({"unchecked", "rawtypes"})
            var mapClass = (Class<Map<String, Object>>) (Class) Map.class;
            var results = fetcher.withSchema(schemaName, () -> {
                if (effectiveQuery.selector() instanceof RootSelector) {
                    return fetcher.queryForRootAsMap(effectiveQuery, effectiveQuery.from().root());
                }
                return fetcher.queryForType(effectiveQuery, () -> mapClass);
            });

            var executionTime = System.currentTimeMillis() - startTime;

            return new QueryResponse(results, results.size(), executionTime);

        } catch (Exception e) {
            log.error("Query execution failed", e);
            throw new RuntimeException("Query execution failed: " + e.getMessage(), e);
        }
    }

    private Query applyLimitCap(Query query) {
        var currentLimit = query.limit();
        if (currentLimit == null || currentLimit > 1000) {
            return query.withLimit((long) 1000);
        }
        return query;
    }

    public QueryResponse executeQuery(Query query) {
        var startTime = System.currentTimeMillis();

        try {
            var effectiveQuery = applyLimitCap(query);

            var results = fetcher.queryForRootAsMap(effectiveQuery, effectiveQuery.from().root());
            var executionTime = System.currentTimeMillis() - startTime;

            return new QueryResponse(results, results.size(), executionTime);

        } catch (Exception e) {
            log.error("Query execution failed", e);
            throw new RuntimeException("Query execution failed: " + e.getMessage(), e);
        }
    }
}
