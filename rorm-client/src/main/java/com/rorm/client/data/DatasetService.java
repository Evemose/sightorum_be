package com.rorm.client.data;

import com.rorm.client.data.dto.DatasetInfo;
import com.rorm.client.data.dto.QueryResponse;
import com.rorm.client.data.dto.SampleResponse;
import com.rorm.client.metamodel.MetamodelService;
import com.rorm.dto.QueryDTO;
import com.rorm.fetcher.Fetcher;
import com.rorm.mapper.QueryMapper;
import com.rorm.metamodel.AliasedRoot;
import com.rorm.metamodel.Attribute;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.Root;
import com.rorm.query.Query;
import com.rorm.query.Selector.RootSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final MetamodelService metamodelService;
    private final QueryMapper queryMapper;
    private final Fetcher fetcher;
    private final DSLContext dsl;

    public List<DatasetInfo> listDatasets() {
        return metamodelService.listMetamodels().stream()
            .map(mm -> getDatasetInfo(mm.schemaName()))
            .toList();
    }

    public DatasetInfo getDatasetInfo(String schemaName) {
        var modelSpace = metamodelService.getModelSpace(schemaName);

        var tables = new ArrayList<DatasetInfo.TableInfo>();
        long totalRows = 0;

        for (var root : modelSpace.roots()) {
            var tableName = root.primaryTableName();
            var columns = getColumnNames(root);
            var rowCount = getTableRowCount(schemaName, tableName);
            totalRows += rowCount;

            tables.add(new DatasetInfo.TableInfo(tableName, rowCount, columns));
        }

        return new DatasetInfo(schemaName, tables, totalRows);
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

        var results = fetcher.queryForRootAsMap(query, root);

        var totalRows = getTableRowCount(schemaName, tableName);

        return new SampleResponse(schemaName, tableName, columns, results, totalRows);
    }

    public QueryResponse executeQuery(String schemaName, QueryDTO queryDTO) {
        var startTime = System.currentTimeMillis();

        try {
            var modelSpace = metamodelService.getModelSpace(schemaName);
            var query = queryMapper.toEntity(queryDTO, modelSpace);

            // Apply limit cap
            var effectiveQuery = applyLimitCap(query);

            var results = fetcher.queryForRootAsMap(effectiveQuery, effectiveQuery.from().root());

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
            // Apply limit cap
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
