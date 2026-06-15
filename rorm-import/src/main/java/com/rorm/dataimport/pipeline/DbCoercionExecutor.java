package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.type.DbLevelCoercion;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DbCoercionExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public PreparedImport execute(PreparedImport prepared) {
        var targets = resolveTargets(prepared.request(), prepared.modelSpace());
        if (!targets.isEmpty()) {
            transactionTemplate.executeWithoutResult(_ -> {
                for (var target : targets) {
                    jdbcTemplate.execute(target.toSql());
                }
            });
        }
        return prepared;
    }

    public Flux<ImportEvent> asFlux(PreparedImport prepared) {
        return Flux.defer(() ->
            Mono.<ImportEvent>fromRunnable(() -> execute(prepared))
                .subscribeOn(Schedulers.boundedElastic())
                .flux()
        );
    }

    List<CoercionTarget> resolveTargets(ImportRequest request, ModelSpace modelSpace) {
        return request.coercionStrategies().entrySet().stream()
            .filter(e -> e.getValue() instanceof DbLevelCoercion)
            .map(e -> toCoercionTarget(e.getKey(), (DbLevelCoercion) e.getValue(), request, modelSpace))
            .toList();
    }

    private CoercionTarget toCoercionTarget(
        ImportRequest.AttributeKey key, DbLevelCoercion strategy, ImportRequest request, ModelSpace modelSpace
    ) {
        var detectedAttribute = resolveDetectedBasicAttribute(request, key);
        var root = findRoot(modelSpace, key.rootName());
        var metamodelAttribute = findBasicAttribute(root, detectedAttribute.name());
        return new CoercionTarget(
            strategy,
            request.targetSchema(),
            key.rootName(),
            metamodelAttribute.location().column(),
            metamodelAttribute.dataType(),
            root.idDescriptor().columnName()
        );
    }

    private DetectedAttribute.Basic resolveDetectedBasicAttribute(ImportRequest request, ImportRequest.AttributeKey key) {
        var detectedRoot = request.detectedSchema().roots().get(key.rootName());
        if (detectedRoot == null) {
            throw new IllegalStateException("Root not found: " + key.rootName());
        }
        var attribute = findAttributeByPath(detectedRoot.attributes(), key.attributePath());
        if (attribute == null) {
            throw new IllegalStateException("Attribute not found: " + key.attributePath());
        }
        if (attribute instanceof DetectedAttribute.Basic basic) {
            return basic;
        }
        throw new IllegalStateException(
            "DbLevelCoercion only applicable to basic attributes, not: " + attribute.getClass().getSimpleName());
    }

    /**
     * Locates the metamodel attribute by its logical name. Detection derives the name from the source header
     * ({@code unit_price} becomes {@code unitPrice}), so the name is the only field shared with the coercion key;
     * the physical column the SQL must address is then read from the resolved attribute's location.
     */
    private BasicAttribute findBasicAttribute(Root root, String attributeName) {
        return root.attributes().stream()
            .filter(BasicAttribute.class::isInstance)
            .map(BasicAttribute.class::cast)
            .filter(attribute -> attribute.name().equals(attributeName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Attribute not found in metamodel: " + attributeName + " on root " + root.primaryTableName()));
    }

    private Root findRoot(ModelSpace modelSpace, String rootName) {
        return modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals(rootName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Root not found: " + rootName));
    }

    private @Nullable DetectedAttribute findAttributeByPath(Map<String, DetectedAttribute> attributes, String path) {
        if (!path.contains(".")) {
            return attributes.get(path);
        }
        var parts = path.split("\\.", 2);
        var first = attributes.get(parts[0]);
        if (first instanceof DetectedAttribute.Composite composite) {
            return findAttributeByPath(composite.subAttributes(), parts[1]);
        }
        return null;
    }

    record CoercionTarget(
        DbLevelCoercion strategy,
        String targetSchema,
        String tableName,
        String columnName,
        DataType dataType,
        String idColumnName
    ) {
        String toSql() {
            return strategy.generateSql(targetSchema, tableName, columnName, dataType, idColumnName);
        }
    }
}
