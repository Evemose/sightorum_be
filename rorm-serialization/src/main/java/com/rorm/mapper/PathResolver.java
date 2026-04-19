package com.rorm.mapper;

import com.rorm.dto.QueryDTO;
import com.rorm.dto.QueryDTO.JoinDTO;
import com.rorm.dto.SelectorDTO;
import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.query.Path;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
class PathResolver {

    /// Resolve a path string to a Path object within the given ModelSpace
    /// If path start does not match any known alias, root is implicitly selected as query FROM root
    public Path resolve(String path, ModelSpace modelSpace, QueryDTO query) {
        var segments = path.split("\\.");
        if (segments.length == 0) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
        var aliases = gatherAliases(modelSpace, query);
        var fromRoot = find(modelSpace, query.from());
        var current = startPathOrProjectionAlias(segments[0], aliases, fromRoot, query);
        var result = new Path(current);
        for (var i = 1; i < segments.length; i++) {
            current = findAttribute(current, segments[i]);
            result = new Path(current, result);
        }
        return result;
    }

    private PathTarget startPathOrProjectionAlias(String firstSegment, Map<String, Root> aliases, Root fromRoot, QueryDTO query) {
        try {
            return startPath(firstSegment, aliases, fromRoot);
        } catch (IllegalArgumentException ex) {
            var synthetic = resolveProjectionAlias(firstSegment, query, fromRoot);
            if (synthetic != null) {
                return synthetic;
            }
            throw ex;
        }
    }

    private PathTarget resolveProjectionAlias(String segment, QueryDTO query, Root fromRoot) {
        if (query.selector() == null) {
            return null;
        }
        return switch (query.selector()) {
            case SelectorDTO.SingleExprSelectorDTO single ->
                isMatchingAlias(single.alias(), segment) ? syntheticAttribute(segment, fromRoot) : null;
            case SelectorDTO.MultiExprSelectorDTO multi -> multi.expressions().stream()
                .map(SelectorDTO.SelectedExpressionDTO::alias)
                .filter(alias -> isMatchingAlias(alias, segment))
                .findFirst()
                .map(_ -> syntheticAttribute(segment, fromRoot))
                .orElse(null);
            case SelectorDTO.RootSelectorDTO _ -> null;
        };
    }

    private boolean isMatchingAlias(String alias, String segment) {
        return alias != null && !alias.isBlank() && alias.equals(segment);
    }

    private BasicAttribute syntheticAttribute(String alias, Root fromRoot) {
        return new BasicAttribute(
            alias,
            new AttributeLocation(fromRoot.primaryTableName(), alias),
            new DataType.StringType()
        );
    }

    private Map<String, Root> gatherAliases(ModelSpace modelSpace, QueryDTO query) {
        var base = Objects.requireNonNullElseGet(query.joins(), List::<JoinDTO>of).stream()
            .map(JoinDTO::joinedRoot)
            .collect(Collectors.toMap(
                jr -> Objects.requireNonNullElse(jr.alias(), jr.rootName()),
                jr -> find(modelSpace, jr.rootName()),
                (a, _) -> {
                    throw new IllegalArgumentException("Duplicate alias: " + a);
                },
                HashMap::new
            ));
        if (query.from() == null) {
            throw new IllegalArgumentException("Query FROM root cannot be null");
        }
        var fromRoot = find(modelSpace, query.from());
        var fromAlias = (query.fromAlias() != null && !query.fromAlias().isBlank())
            ? query.fromAlias()
            : query.from();

        if (base.containsKey(fromAlias)) {
            throw new IllegalArgumentException("FROM alias conflicts with join alias: " + fromAlias);
        }
        base.put(fromAlias, fromRoot);

        return base;
    }

    private PathTarget startPath(String segment, Map<String, Root> aliases, Root fromRoot) {
        var root = aliases.get(segment);
        if (root == null) {
            return findAttribute(AliasedRoot.of(fromRoot), segment);
        } else {
            return AliasedRoot.of(root, segment);
        }
    }

    private Root find(ModelSpace modelSpace, String name) {
        return modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No root found with name: " + name));
    }

    private static PathTarget findAttribute(PathTarget pathTarget, String segment) {
        var attributes = switch (pathTarget) {
            case AliasedRoot jr -> jr.root().attributes();
            case CompositeAttribute ca -> ca.attributes();
            case ReferenceAttribute ra -> ra.targetRoot().attributes();
            case CompositeElement ce -> ce.attributes();
            default -> throw new IllegalArgumentException("Cannot navigate through path target: " + pathTarget);
        };
        return attributes.stream()
            .filter(attr -> attr.name().equals(segment))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No attribute found with name: " + segment + " in path target: " + pathTarget));
    }

}
