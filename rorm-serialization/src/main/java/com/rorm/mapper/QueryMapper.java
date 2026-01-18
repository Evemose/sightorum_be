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
import com.rorm.metamodel.CollectionAttribute.CollectionElement;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.RootSelector;
import com.rorm.query.Selector.SingleExprSelector;
import lombok.AccessLevel;
import lombok.Setter;
import org.mapstruct.*;

import java.util.LinkedHashSet;
import java.util.SequencedSet;

@Mapper(builder = @Builder(disableBuilder = true))
public abstract class QueryMapper {

    @Setter(AccessLevel.PACKAGE)
    private PathResolver pathResolver;

    @Mapping(target = "fromAlias", source = "from.alias")
    public abstract QueryDTO toDTO(Query query);

    public Query toEntity(QueryDTO dto, @Context ModelSpace modelSpace) {
        return toEntityInternal(dto, modelSpace, dto);
    }

    @Named("queryDtoToEntityInternal")
    protected abstract Query toEntityInternal(QueryDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO sameDto);

    protected abstract GroupByDTO toDTO(GroupBy groupBy);

    protected abstract GroupBy toEntity(GroupByDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected abstract OrderByDTO toDTO(OrderBy orderBy);

    protected abstract OrderBy toEntity(OrderByDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

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

    // Expressions - bidirectional (public for ExpressionMapper access)
    @SubclassMapping(source = Path.class, target = PathDTO.class)
    @SubclassMapping(source = Literal.class, target = LiteralDTO.class)
    @SubclassMapping(source = FunctionCall.class, target = FunctionCallDTO.class)
    @SubclassMapping(source = Aggregation.class, target = AggregationDTO.class)
    @SubclassMapping(source = WindowFunction.class, target = WindowFunctionDTO.class)
    @SubclassMapping(source = BinaryExpression.class, target = BinaryExpressionDTO.class)
    @SubclassMapping(source = UnaryExpression.class, target = UnaryExpressionDTO.class)
    @SubclassMapping(source = TernaryExpression.class, target = TernaryExpressionDTO.class)
    @SubclassMapping(source = Subquery.class, target = SubqueryDTO.class)
    @SubclassMapping(source = OuterRef.class, target = OuterRefDTO.class)
    public abstract ExpressionDTO toDTO(Expression expression);

    @SubclassMapping(source = PathDTO.class, target = Path.class)
    @SubclassMapping(source = LiteralDTO.class, target = Literal.class)
    @SubclassMapping(source = FunctionCallDTO.class, target = FunctionCall.class)
    @SubclassMapping(source = AggregationDTO.class, target = Aggregation.class)
    @SubclassMapping(source = WindowFunctionDTO.class, target = WindowFunction.class)
    @SubclassMapping(source = BinaryExpressionDTO.class, target = BinaryExpression.class)
    @SubclassMapping(source = UnaryExpressionDTO.class, target = UnaryExpression.class)
    @SubclassMapping(source = TernaryExpressionDTO.class, target = TernaryExpression.class)
    @SubclassMapping(source = SubqueryDTO.class, target = Subquery.class)
    @SubclassMapping(source = OuterRefDTO.class, target = OuterRef.class)
    public abstract Expression toEntity(ExpressionDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    @Mapping(target = "path", expression = "java(mapPath(path))")
    protected abstract PathDTO toDTO(Path path);

    protected Path toEntity(PathDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query) {
        if (dto == null) {
            return null;
        }
        return mapPath(dto.path(), modelSpace, query);
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

    protected abstract UnaryExpressionDTO toDTO(UnaryExpression unaryExpression);

    protected abstract UnaryExpression toEntity(UnaryExpressionDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected abstract TernaryExpressionDTO toDTO(TernaryExpression ternaryExpression);

    protected abstract TernaryExpression toEntity(TernaryExpressionDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected abstract SubqueryDTO toDTO(Subquery subquery);

    @Mapping(target = "query", source = "query", qualifiedByName = "queryDtoToEntityInternal")
    protected abstract Subquery toEntity(SubqueryDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

    protected abstract OuterRefDTO toDTO(OuterRef outerRef);

    protected abstract OuterRef toEntity(OuterRefDTO dto, @Context ModelSpace modelSpace, @Context QueryDTO query);

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
