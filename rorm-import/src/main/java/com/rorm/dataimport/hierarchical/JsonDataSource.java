package com.rorm.dataimport.hierarchical;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * JSON data source with streaming support for large files.
 */
public class JsonDataSource extends AbstractHierarchicalDataSource {

    private final ObjectMapper objectMapper;

    @Nullable
    private JsonParser currentParser;
    @Nullable
    private InputStream currentInputStream;

    public JsonDataSource(Path filePath) {
        this(filePath, List.of());
    }

    public JsonDataSource(Path filePath, List<HierarchicalOverride> overrides) {
        super(filePath, overrides);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected List<JsonNode> readSampleNodes() {
        try (var is = Files.newInputStream(filePath);
             var parser = objectMapper.getFactory().createParser(is)) {

            var token = parser.nextToken();
            if (token != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Expected JSON array at root");
            }

            var sampleNodes = new ArrayList<JsonNode>();
            int count = 0;

            while (parser.nextToken() != JsonToken.END_ARRAY && count < STRUCTURE_SAMPLE_SIZE) {
                var node = objectMapper.readValue(parser, JsonNode.class);
                if (node.isObject()) {
                    sampleNodes.add(node);
                    count++;
                }
            }

            return sampleNodes;

        } catch (IOException e) {
            throw new RuntimeException("Failed to analyze JSON structure: " + filePath, e);
        }
    }

    @Override
    public Stream<Map<String, Object>> stream() {
        try {
            currentInputStream = Files.newInputStream(filePath);
            currentParser = objectMapper.getFactory().createParser(currentInputStream);

            var token = currentParser.nextToken();
            if (token != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Expected JSON array at root, found: " + token);
            }

            var columnNames = getColumnNames();
            var iterator = new JsonStreamIterator(currentParser, objectMapper, columnNames);

            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED),
                    false
                )
                .onClose(this::close);

        } catch (IOException e) {
            throw new RuntimeException("Failed to create JSON stream for: " + filePath, e);
        }
    }

    @Override
    public void close() {
        closeQuietly(currentParser);
        closeQuietly(currentInputStream);
        currentParser = null;
        currentInputStream = null;
    }

    private static class JsonStreamIterator implements Iterator<Map<String, Object>> {

        private final JsonParser parser;
        private final ObjectMapper mapper;
        private final List<String> columnNames;
        private boolean hasNext = true;
        private long rowIndex = 0;

        JsonStreamIterator(JsonParser parser, ObjectMapper mapper, List<String> columnNames) {
            this.parser = parser;
            this.mapper = mapper;
            this.columnNames = columnNames;
        }

        @Override
        public boolean hasNext() {
            if (!hasNext) {
                return false;
            }
            try {
                var token = parser.nextToken();
                if (token == JsonToken.END_ARRAY || token == null) {
                    hasNext = false;
                    return false;
                }
                return true;
            } catch (IOException e) {
                throw new RuntimeException("Error reading JSON", e);
            }
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            try {
                var node = mapper.readValue(parser, JsonNode.class);
                rowIndex++;
                return AbstractHierarchicalDataSource.flattenNode(node, columnNames, rowIndex);
            } catch (IOException e) {
                throw new RuntimeException("Error parsing JSON object", e);
            }
        }
    }
}
