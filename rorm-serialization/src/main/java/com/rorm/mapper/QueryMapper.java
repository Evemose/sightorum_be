package com.rorm.mapper;

import com.rorm.dto.ExpressionDTO;
import com.rorm.dto.ExpressionDTO.*;
import com.rorm.dto.QueryDTO;
import com.rorm.dto.QueryDTO.*;
import com.rorm.dto.SelectorDTO;
import com.rorm.dto.SelectorDTO.MultiExprSelectorDTO;
import com.rorm.dto.SelectorDTO.RootSelectorDTO;
import com.rorm.dto.SelectorDTO.SelectedExpressionDTO;
import com.rorm.dto.SelectorDTO.SingleExprSelectorDTO;
import com.rorm.metamodel.AliasedRoot;
import com.rorm.metamodel.Attribute;
import com.rorm.metamodel.AttributeLocation;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.CollectionAttribute.CollectionElement;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import com.rorm.query.StandardOperator;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.RootSelector;
import com.rorm.query.Selector.SingleExprSelector;
import org.mapstruct.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Deque;
import java.util.SequencedSet;
import java.util.Set;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, builder = @Builder(disableBuilder = true))
public abstract class QueryMapper {

    @Autowired
    private PathResolver pathResolver;

    private final ThreadLocal<Deque<QueryDTO>> queryScope = ThreadLocal.withInitial(ArrayDeque::new);

    @Mapping(target = "fromAlias", source = "from.alias")
    public abstract QueryDTO toDTO(Query query);

    protected OrderByDTO toDTO(OrderBy orderBy) {
        if (orderBy == null) {
            return null;
        }
        return new OrderByDTO(
            toDTO(orderBy.expression()),
            orderBy.ascending(),
            orderBy.nullsHandling() != null ? orderBy.nullsHandling().name() : null
        );
    }

    @Named("queryDtoToEntityInternal")
    protected abstract Query toEntityInternal(QueryDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO sameDto);

    protected abstract GroupByDTO toDTO(GroupBy groupBy);

    protected abstract GroupBy toEntity(GroupByDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    // Expressions - bidirectional (public for ExpressionMapper access)
    @SubclassMapping(source = Path.class, target = PathDTO.class)
    @SubclassMapping(source = Literal.class, target = LiteralDTO.class)
    @SubclassMapping(source = FunctionCall.class, target = FunctionCallDTO.class)
    @SubclassMapping(source = Aggregation.class, target = AggregationDTO.class)
    @SubclassMapping(source = WindowFunction.class, target = WindowFunctionDTO.class)
    @SubclassMapping(source = BinaryExpression.class, target = BinaryExpressionDTO.class)
    @SubclassMapping(source = QuantifiedComparison.class, target = QuantifiedComparisonDTO.class)
    @SubclassMapping(source = UnaryExpression.class, target = UnaryExpressionDTO.class)
    @SubclassMapping(source = TernaryExpression.class, target = TernaryExpressionDTO.class)
    @SubclassMapping(source = Subquery.class, target = SubqueryDTO.class)
    @SubclassMapping(source = CaseExpression.class, target = ExpressionDTO.CaseExpressionDTO.class)
    public abstract ExpressionDTO toDTO(Expression expression);

    protected OrderBy toEntity(OrderByDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query) {
        if (dto == null) {
            return null;
        }
        return new OrderBy(
            toEntity(dto.expression(), modelSpace, query),
            dto.ascending(),
            dto.nullsHandling() != null ? OrderBy.NullsHandling.valueOf(dto.nullsHandling()) : null
        );
    }

    @Mapping(target = "joinedRoot", source = "aliasedRoot")
    protected abstract JoinDTO toDTO(Join join);

    @Mapping(target = "aliasedRoot", source = "joinedRoot")
    protected abstract Join toEntity(JoinDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    // JoinedRoot mapping
    protected JoinedRootDTO toDTO(AliasedRoot aliasedRoot) {
        if (aliasedRoot == null) {
            return null;
        }
        return new JoinedRootDTO(aliasedRoot.root().primaryTableName(), aliasedRoot.alias());
    }

    protected AliasedRoot toEntity(JoinedRootDTO dto, @Context ModelSpace modelSpace) {
        if (dto == null) {
            return null;
        }
        var root = findRootByName(dto.rootName(), modelSpace);
        var alias = dto.alias() != null ? dto.alias() : dto.rootName();
        return AliasedRoot.of(root, alias);
    }

    private Root findRootByName(String rootName, ModelSpace modelSpace) {
        return modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals(rootName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown root name: " + rootName));
    }

    protected abstract WindowSpecDTO toDTO(WindowSpec windowSpec);

    protected abstract WindowSpec toEntity(WindowSpecDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    @SubclassMapping(source = PathDTO.class, target = Path.class)
    @SubclassMapping(source = LiteralDTO.class, target = Literal.class)
    @SubclassMapping(source = FunctionCallDTO.class, target = FunctionCall.class)
    @SubclassMapping(source = AggregationDTO.class, target = Aggregation.class)
    @SubclassMapping(source = WindowFunctionDTO.class, target = WindowFunction.class)
    @SubclassMapping(source = BinaryExpressionDTO.class, target = BinaryExpression.class)
    @SubclassMapping(source = QuantifiedComparisonDTO.class, target = QuantifiedComparison.class)
    @SubclassMapping(source = UnaryExpressionDTO.class, target = UnaryExpression.class)
    @SubclassMapping(source = TernaryExpressionDTO.class, target = TernaryExpression.class)
    @SubclassMapping(source = SubqueryDTO.class, target = Subquery.class)
    @SubclassMapping(source = ExpressionDTO.CaseExpressionDTO.class, target = CaseExpression.class)
    public abstract Expression toEntity(ExpressionDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    // WindowFrame mapping (String ↔ enum conversion)
    protected WindowFrameDTO toDTO(WindowFrame frame) {
        if (frame == null) {
            return null;
        }
        return new WindowFrameDTO(
            frame.type().name(),
            toDTO(frame.start()),
            toDTO(frame.end())
        );
    }

    protected FrameBoundDTO toDTO(WindowFrame.FrameBound bound) {
        if (bound == null) {
            return null;
        }
        return new FrameBoundDTO(bound.type().name(), bound.offset());
    }

    protected WindowFrame toEntity(WindowFrameDTO dto) {
        if (dto == null) {
            return null;
        }
        return new WindowFrame(
            WindowFrame.FrameType.valueOf(dto.type()),
            toEntity(dto.start()),
            toEntity(dto.end())
        );
    }

    // Selectors - bidirectional
    @SubclassMapping(source = RootSelector.class, target = RootSelectorDTO.class)
    @SubclassMapping(source = SingleExprSelector.class, target = SingleExprSelectorDTO.class)
    @SubclassMapping(source = MultiExprSelector.class, target = MultiExprSelectorDTO.class)
    protected abstract SelectorDTO toDTO(Selector selector);

    @SubclassMapping(source = RootSelectorDTO.class, target = RootSelector.class)
    @SubclassMapping(source = SingleExprSelectorDTO.class, target = SingleExprSelector.class)
    @SubclassMapping(source = MultiExprSelectorDTO.class, target = MultiExprSelector.class)
    protected abstract Selector toEntity(SelectorDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected RootSelectorDTO toDTO(RootSelector selector) {
        if (selector == null) {
            return null;
        }
        return new RootSelectorDTO(selector.root().primaryTableName(), selector.distinct());
    }

    protected RootSelector toEntity(RootSelectorDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query) {
        if (dto == null) {
            return null;
        }
        var root = findRootByName(dto.rootName(), modelSpace);
        return new RootSelector(root, dto.distinct());
    }

    protected abstract SingleExprSelectorDTO toDTO(SingleExprSelector selector);

    protected abstract SingleExprSelector toEntity(SingleExprSelectorDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected abstract MultiExprSelectorDTO toDTO(MultiExprSelector selector);

    protected abstract MultiExprSelector toEntity(MultiExprSelectorDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected abstract SelectedExpressionDTO toDTO(SelectedExpression selectedExpression);

    protected abstract SelectedExpression toEntity(SelectedExpressionDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected WindowFrame.FrameBound toEntity(FrameBoundDTO dto) {
        if (dto == null) {
            return null;
        }
        return new WindowFrame.FrameBound(WindowFrame.BoundType.valueOf(dto.type()), dto.offset());
    }

    protected Path toEntity(PathDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query) {
        if (dto == null) {
            return null;
        }
        try {
            return mapPath(dto.path(), modelSpace, query);
        } catch (IllegalArgumentException ex) {
            for (var ancestor : queryScope.get()) {
                if (ancestor == query) {
                    continue;
                }
                try {
                    return mapPath(dto.path(), modelSpace, ancestor);
                } catch (IllegalArgumentException ignored) {
                }
            }
            throw ex;
        }
    }

    @Mapping(target = "path", expression = "java(mapPath(path))")
    protected abstract PathDTO toDTO(Path path);

    protected QuantifiedComparisonDTO toDTO(QuantifiedComparison quantified) {
        if (quantified == null) {
            return null;
        }
        return new QuantifiedComparisonDTO(
            toDTO(quantified.left()),
            com.rorm.query.Operator.BinaryOperator.valueOf(quantified.comparison().name()),
            QuantifierDTO.valueOf(quantified.quantifier().name()),
            toDTO(quantified.subquery())
        );
    }

    protected Path mapPath(String pathString, @Context ModelSpace modelSpace, @Context QueryDTO queryDTO) {
        if (pathString == null || pathString.isEmpty()) {
            return null;
        }
        return pathResolver.resolve(pathString, modelSpace, queryDTO);
    }

    protected abstract LiteralDTO toDTO(Literal literal);

    protected abstract Literal toEntity(LiteralDTO dto);

    protected abstract FunctionCallDTO toDTO(FunctionCall functionCall);

    protected abstract FunctionCall toEntity(FunctionCallDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected abstract AggregationDTO toDTO(Aggregation aggregation);

    protected abstract Aggregation toEntity(AggregationDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected abstract WindowFunctionDTO toDTO(WindowFunction windowFunction);

    protected abstract WindowFunction toEntity(WindowFunctionDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected abstract BinaryExpressionDTO toDTO(BinaryExpression binaryExpression);

    protected abstract BinaryExpression toEntity(BinaryExpressionDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected QuantifiedComparison toEntity(QuantifiedComparisonDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query) {
        if (dto == null) {
            return null;
        }
        return new QuantifiedComparison(
            toEntity(dto.left(), modelSpace, query),
            StandardOperator.Binary.valueOf(dto.comparison().name()),
            QuantifiedComparison.Quantifier.valueOf(dto.quantifier().name()),
            toEntity(dto.subquery(), modelSpace, query)
        );
    }

    protected Subquery toEntity(SubqueryDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query) {
        if (dto == null) {
            return null;
        }
        // Important: subquery path resolution must use subquery DTO context, not outer query context.
        var scope = queryScope.get();
        scope.push(dto.query());
        try {
            return new Subquery(toEntityInternal(dto.query(), augmentModelSpace(modelSpace, dto.query()), dto.query()));
        } finally {
            scope.pop();
        }
    }

    protected abstract UnaryExpressionDTO toDTO(UnaryExpression unaryExpression);

    protected abstract UnaryExpression toEntity(UnaryExpressionDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected abstract TernaryExpressionDTO toDTO(TernaryExpression ternaryExpression);

    protected abstract TernaryExpression toEntity(TernaryExpressionDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected abstract SubqueryDTO toDTO(Subquery subquery);

    private ModelSpace augmentModelSpace(ModelSpace baseModelSpace, QueryDTO queryDto) {
        if (queryDto == null || queryDto.ctes() == null || queryDto.ctes().isEmpty()) {
            return baseModelSpace;
        }

        var rootsByName = new LinkedHashMap<String, Root>();
        for (var root : baseModelSpace.roots()) {
            rootsByName.put(root.primaryTableName(), root);
        }

        for (var cte : queryDto.ctes()) {
            var cteRoot = buildSyntheticCteRoot(cte, rootsByName);
            rootsByName.put(cteRoot.primaryTableName(), cteRoot);
        }

        return new ModelSpace(Set.copyOf(rootsByName.values()));
    }

    private Root buildSyntheticCteRoot(CteDTO cte, Map<String, Root> availableRootsByName) {
        var tableName = cte.name();
        var columnNames = resolveCteColumnNames(cte, availableRootsByName);

        List<com.rorm.metamodel.Attribute> attributes = columnNames.stream()
            .distinct()
            .<com.rorm.metamodel.Attribute>map(col -> new BasicAttribute(col, new AttributeLocation(tableName, col), new DataType.StringType()))
            .toList();

        var idAttr = attributes.stream()
            .filter(BasicAttribute.class::isInstance)
            .map(BasicAttribute.class::cast)
            .filter(attr -> "id".equals(attr.name()))
            .findFirst()
            .orElse(null);

        var idDescriptor = idAttr != null
            ? new com.rorm.metamodel.IdDescriptor(idAttr)
            : com.rorm.metamodel.IdDescriptor.longId(tableName);

        return new Root(tableName, attributes, idDescriptor);
    }

    private List<String> resolveCteColumnNames(CteDTO cte, Map<String, Root> availableRootsByName) {
        if (cte.columns() != null && !cte.columns().isEmpty()) {
            return cte.columns();
        }

        var selector = cte.query().selector();
        return switch (selector) {
            case RootSelectorDTO rootSelector -> {
                var root = availableRootsByName.get(rootSelector.rootName());
                if (root == null) {
                    throw new IllegalArgumentException("Unknown root name: " + rootSelector.rootName());
                }
                yield root.attributes().stream()
                    .filter(BasicAttribute.class::isInstance)
                    .map(BasicAttribute.class::cast)
                    .map(BasicAttribute::name)
                    .toList();
            }
            case SingleExprSelectorDTO single -> List.of(
                single.alias() != null && !single.alias().isBlank() ? single.alias() : "col1"
            );
            case MultiExprSelectorDTO multi -> {
                var names = new java.util.ArrayList<String>();
                var index = 1;
                for (var expr : multi.expressions()) {
                    if (expr.alias() != null && !expr.alias().isBlank()) {
                        names.add(expr.alias());
                    } else {
                        names.add("col" + index++);
                    }
                }
                yield names;
            }
        };
    }

    // CaseExpression mapping (manual — nested WhenClause conversion)
    protected ExpressionDTO.CaseExpressionDTO toDTO(CaseExpression caseExpr) {
        if (caseExpr == null) {
            return null;
        }
        return new ExpressionDTO.CaseExpressionDTO(
            caseExpr.whens().stream()
                .map(w -> new ExpressionDTO.WhenClauseDTO(toDTO(w.condition()), toDTO(w.result())))
                .toList(),
            caseExpr.elseExpr() != null ? toDTO(caseExpr.elseExpr()) : null
        );
    }

    protected CaseExpression toEntity(ExpressionDTO.CaseExpressionDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query) {
        if (dto == null) {
            return null;
        }
        return new CaseExpression(
            dto.whens().stream()
                .map(w -> new CaseExpression.WhenClause(toEntity(w.condition(), modelSpace, query), toEntity(w.result(), modelSpace, query)))
                .toList(),
            dto.elseExpr() != null ? toEntity(dto.elseExpr(), modelSpace, query) : null
        );
    }

    // CTE mapping (manual — CteDefinition ↔ CteDTO)
    protected CteDTO toDTO(CteDefinition cte) {
        if (cte == null) {
            return null;
        }
        return new CteDTO(cte.name(), toDTO(cte.query()), cte.columns());
    }

    protected CteDefinition toEntity(CteDTO dto, @Context ModelSpace modelSpace) {
        if (dto == null) {
            return null;
        }
        return new CteDefinition(dto.name(), toEntity(dto.query(), modelSpace), dto.columns());
    }

    public Query toEntity(QueryDTO dto, @Context ModelSpace modelSpace) {
        var scope = queryScope.get();
        scope.push(dto);
        try {
            return toEntityInternal(dto, augmentModelSpace(modelSpace, dto), dto);
        } finally {
            scope.pop();
            if (scope.isEmpty()) {
                queryScope.remove();
            }
        }
    }

    // SetOperation mapping (manual — SetOperation ↔ SetOperationDTO)
    protected SetOperationDTO toDTO(SetOperation setOp) {
        if (setOp == null) {
            return null;
        }
        return new SetOperationDTO(setOp.type().name(), toDTO(setOp.query()));
    }

    protected SetOperation toEntity(SetOperationDTO dto, @Context ModelSpace modelSpace) {
        if (dto == null) {
            return null;
        }
        return new SetOperation(SetOperation.SetOperationType.valueOf(dto.type()), toEntity(dto.query(), modelSpace));
    }

    protected <T> SequencedSet<T> createSequencedSet() {
        return new LinkedHashSet<>();
    }

    protected String map(AliasedRoot from) {
        return from != null ? from.root().primaryTableName() : null;
    }

    protected AliasedRoot map(String rootName, @Context ModelSpace modelSpace, @Context QueryDTO query) {
        if (rootName == null) {
            return null;
        }
        var root = modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals(rootName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown root name: " + rootName));
        var alias = query.fromAlias() != null && !query.fromAlias().isBlank() ? query.fromAlias() : rootName;
        return AliasedRoot.of(root, alias);
    }

    protected String mapPath(Path path) {
        if (path == null) {
            return null;
        }
        var sb = new StringBuilder();
        buildPathString(path, sb);
        return sb.toString();
    }

    private void buildPathString(Path path, StringBuilder sb) {
        if (path.parent() != null) {
            buildPathString(path.parent(), sb);
            sb.append(".");
        }
        sb.append(getTargetName(path.target()));
    }

    private String getTargetName(com.rorm.metamodel.PathTarget target) {
        return switch (target) {
            case Attribute attr -> attr.name();
            case CollectionElement _ -> "@element";
            case AliasedRoot aliasedRoot -> aliasedRoot.alias();
        };
    }
}
