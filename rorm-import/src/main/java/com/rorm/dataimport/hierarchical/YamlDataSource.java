package com.rorm.dataimport.hierarchical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * YAML data source with streaming support.
 * Supports YAML files containing a sequence of mappings or a single document.
 */
public class YamlDataSource extends AbstractHierarchicalDataSource {

    private final ObjectMapper yamlMapper;

    @Nullable
    private InputStream currentInputStream;

    public YamlDataSource(Path filePath) {
        super(filePath);
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    @Override
    protected List<JsonNode> readSampleNodes() {
        try (var is = Files.newInputStream(filePath)) {
            var rootNode = yamlMapper.readTree(is);

            if (rootNode == null) {
                return List.of();
            }

            if (rootNode.isArray()) {
                var sampleNodes = new ArrayList<JsonNode>();
                var iter = rootNode.elements();
                int count = 0;
                while (iter.hasNext() && count < STRUCTURE_SAMPLE_SIZE) {
                    var node = iter.next();
                    if (node.isObject()) {
                        sampleNodes.add(node);
                        count++;
                    }
                }
                return sampleNodes;
            } else if (rootNode.isObject()) {
                return List.of(rootNode);
            }

            return List.of();

        } catch (IOException e) {
            throw new RuntimeException("Failed to analyze YAML structure: " + filePath, e);
        }
    }

    @Override
    public long countRows() {
        try (var is = Files.newInputStream(filePath)) {
            var rootNode = yamlMapper.readTree(is);
            if (rootNode == null) {
                return 0;
            }
            if (rootNode.isArray()) {
                return rootNode.size();
            }
            if (rootNode.isObject()) {
                return 1;
            }
            return 0;
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public Stream<Map<String, Object>> stream() {
        try {
            currentInputStream = Files.newInputStream(filePath);
            var rootNode = yamlMapper.readTree(currentInputStream);

            if (rootNode == null) {
                return Stream.empty();
            }

            var columnNames = getColumnNames();
            List<JsonNode> nodes;

            if (rootNode.isArray()) {
                nodes = new ArrayList<>();
                rootNode.elements().forEachRemaining(nodes::add);
            } else if (rootNode.isObject()) {
                nodes = List.of(rootNode);
            } else {
                return Stream.empty();
            }

            var rowIndex = new long[]{0};
            return nodes.stream()
                .map(node -> {
                    rowIndex[0]++;
                    return AbstractHierarchicalDataSource.flattenNode(node, columnNames, rowIndex[0]);
                })
                .onClose(this::close);

        } catch (IOException e) {
            throw new RuntimeException("Failed to create YAML stream for: " + filePath, e);
        }
    }

    @Override
    public void close() {
        closeQuietly(currentInputStream);
        currentInputStream = null;
    }
}
