package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.pipeline.SchemaDetector.DetectedRoot;
import com.rorm.metamodel.Root;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes data from a single ImportDataSource to multiple database tables.
 *
 * <p>This writer handles implicit one-to-one relationships where a single CSV row
 * contains data for multiple roots. Each root gets its own subset of columns
 * based on the source mappings established during schema detection.
 */
class MultiRootItemWriter implements ItemWriter<Map<String, Object>>, ChunkListener {

    private final List<DatabaseItemWriter> delegates;

    /**
     * Creates a multi-root writer for all roots that source from the given datasource.
     *
     * @param jdbcTemplate        JDBC template for database operations
     * @param schema              Target database schema
     * @param detectedRoots       All detected roots that source from this datasource
     * @param rootMap             Map of root name to Root for IdDescriptor lookup
     * @param coercionStrategies  Coercion strategies for handling invalid values
     */
    MultiRootItemWriter(
        JdbcTemplate jdbcTemplate,
        String schema,
        List<DetectedRoot> detectedRoots,
        Map<String, Root> rootMap,
        Map<ImportRequest.AttributeKey, com.rorm.dataimport.type.InvalidValueCoercionStrategy> coercionStrategies
    ) {
        this.delegates = new ArrayList<>();
        var mappingBuilder = new ColumnMappingBuilder();

        for (var detectedRoot : detectedRoots) {
            var root = rootMap.get(detectedRoot.name());
            if (root == null) {
                throw new IllegalStateException("Root not found in modelSpace: " + detectedRoot.name());
            }

            var columnMappings = mappingBuilder.buildMappings(detectedRoot);

            var writer = new DatabaseItemWriter(
                jdbcTemplate,
                schema,
                detectedRoot.name(),
                root.idDescriptor(),
                columnMappings,
                coercionStrategies
            );
            delegates.add(writer);
        }
    }

    @Override
    public void beforeChunk(ChunkContext context) {
        for (var delegate : delegates) {
            delegate.beforeChunk(context);
        }
    }

    @Override
    public void write(Chunk<? extends Map<String, Object>> chunk) throws Exception {
        // Write to all delegate writers - each handles its own columns
        for (var delegate : delegates) {
            delegate.write(chunk);
        }
    }
}
