package com.rorm.mapper;

import com.rorm.dto.QueryDTO;
import com.rorm.dto.QueryDTO.JoinDTO;
import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.query.Path;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

class PathResolver {

    /// Resolve a path string to a Path object within the given ModelSpace
    /// If path start does not match any known alias, root is implicitly selected as query FROM root
    public Path resolve(String path, ModelSpace modelSpace, QueryDTO query) {
        var segments = path.split("\\.");
        if (segments.length == 0) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
        var aliases = gatherAliases(modelSpace, query);
        var current = startPath(segments[0], aliases, find(modelSpace, query.from()));
        var result = new Path(current);
        for (var i = 1; i < segments.length; i++) {
            current = findAttribute(current, segments[i]);
            result = new Path(current, result);
        }
        return result;
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
