package com.rorm.mapper;

import com.rorm.dto.ExpressionDTO;
import com.rorm.dto.ExpressionDTO.*;
import com.rorm.dto.QueryDTO;
import com.rorm.dto.SelectorDTO;
import com.rorm.dto.SelectorDTO.MultiExprSelectorDTO;
import com.rorm.dto.SelectorDTO.RootSelectorDTO;
import com.rorm.dto.SelectorDTO.SelectedExpressionDTO;
import com.rorm.dto.SelectorDTO.SingleExprSelectorDTO;
import com.rorm.dto.dense.DenseExpressionDto;
import com.rorm.dto.dense.DenseQueryDto;
import com.rorm.dto.dense.DenseQueryDto.*;
import com.rorm.dto.dense.DenseSelectorDto;
import com.rorm.metamodel.ModelSpace;
import com.rorm.query.Expression;
import com.rorm.query.Operator.BinaryOperator;
import com.rorm.query.Operator.TernaryOperator;
import com.rorm.query.Operator.UnaryOperator;
import com.rorm.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DenseQueryMapper {

    private final QueryMapper queryMapper;

    // === Dense → Domain ===

    public Query toEntity(DenseQueryDto dto, ModelSpace modelSpace) {
        return queryMapper.toEntity(toQueryDTO(dto), modelSpace);
    }

    public Expression expressionToEntity(DenseExpressionDto dto, ModelSpace modelSpace, String rootName) {
        var dummyQuery = new QueryDTO(rootName, null, null, new LinkedHashSet<>(), null, null, null, null, null, null);
        return queryMapper.toEntity(toExpressionDTO(dto), modelSpace, dummyQuery);
    }

    // === Domain → Dense ===

    public DenseQueryDto toDTO(Query query) {
        return fromQueryDTO(queryMapper.toDTO(query));
    }

    public DenseExpressionDto expressionToDTO(Expression expression) {
        return fromExpressionDTO(queryMapper.toDTO(expression));
    }

    // === Dense → Old DTO ===

    public QueryDTO toQueryDTO(DenseQueryDto dense) {
        if (dense == null) {
            return null;
        }
        return new QueryDTO(
            dense.from(),
            dense.fromAlias(),
            toSelectorDTO(dense.selector()),
            dense.joins() == null ? null : dense.joins().stream()
                .map(this::toJoinDTO)
                .collect(Collectors.toCollection(LinkedHashSet::new)),
            toExpressionDTO(dense.where()),
            dense.groupBy() == null ? null : new QueryDTO.GroupByDTO(
                dense.groupBy().expressions().stream().map(this::toExpressionDTO).toList()
            ),
            toExpressionDTO(dense.having()),
            dense.orderBy() == null ? null : dense.orderBy().stream()
                .map(ob -> new QueryDTO.OrderByDTO(toExpressionDTO(ob.expression()), ob.ascending()))
                .toList(),
            dense.limit(),
            dense.offset()
        );
    }

    ExpressionDTO toExpressionDTO(DenseExpressionDto dense) {
        if (dense == null) {
            return null;
        }
        return switch (dense.type()) {
            case "path" -> new PathDTO(dense.path());
            case "literal" -> new LiteralDTO(dense.value());
            case "function" -> new FunctionCallDTO(
                dense.functionName(),
                mapArgs(dense.arguments())
            );
            case "aggregation" -> new AggregationDTO(
                dense.functionName(),
                mapArgs(dense.arguments()),
                dense.distinct() != null && dense.distinct()
            );
            case "binary" -> new BinaryExpressionDTO(
                toExpressionDTO(dense.left()),
                BinaryOperator.valueOf(dense.operator()),
                toExpressionDTO(dense.right())
            );
            case "unary" -> new UnaryExpressionDTO(
                UnaryOperator.valueOf(dense.operator()),
                toExpressionDTO(dense.operand())
            );
            case "ternary" -> new TernaryExpressionDTO(
                toExpressionDTO(dense.first()),
                TernaryOperator.valueOf(dense.operator()),
                toExpressionDTO(dense.second()),
                toExpressionDTO(dense.third())
            );
            case "window" -> new WindowFunctionDTO(
                dense.functionName(),
                mapArgs(dense.arguments()),
                toWindowSpecDTO(dense.windowSpec())
            );
            case "subquery" -> new SubqueryDTO(toQueryDTO(dense.query()));
            case "outerRef" -> new OuterRefDTO(dense.depth(), dense.path());
            default -> throw new IllegalArgumentException("Unknown expression type: " + dense.type());
        };
    }

    private SelectorDTO toSelectorDTO(DenseSelectorDto dense) {
        if (dense == null) {
            return null;
        }
        return switch (dense.type()) {
            case "root" -> new RootSelectorDTO(dense.rootName(), dense.distinct());
            case "single" -> new SingleExprSelectorDTO(
                toExpressionDTO(dense.expression()),
                dense.distinct(),
                dense.alias()
            );
            case "multi" -> new MultiExprSelectorDTO(
                dense.expressions() == null ? Set.of() : dense.expressions().stream()
                    .map(se -> new SelectedExpressionDTO(toExpressionDTO(se.expression()), se.alias()))
                    .collect(Collectors.toSet()),
                dense.distinct()
            );
            default -> throw new IllegalArgumentException("Unknown selector type: " + dense.type());
        };
    }

    private QueryDTO.JoinDTO toJoinDTO(JoinDto dense) {
        return new QueryDTO.JoinDTO(
            new QueryDTO.JoinedRootDTO(dense.joinedRoot().rootName(), dense.joinedRoot().alias()),
            dense.joinType(),
            toExpressionDTO(dense.onCondition())
        );
    }

    private QueryDTO.WindowSpecDTO toWindowSpecDTO(WindowSpecDto dense) {
        if (dense == null) {
            return null;
        }
        return new QueryDTO.WindowSpecDTO(
            dense.partitionBy() == null ? null : dense.partitionBy().stream().map(this::toExpressionDTO).toList(),
            dense.orderBy() == null ? null : dense.orderBy().stream()
                .map(ob -> new QueryDTO.OrderByDTO(toExpressionDTO(ob.expression()), ob.ascending()))
                .toList()
        );
    }

    private List<ExpressionDTO> mapArgs(List<DenseExpressionDto> args) {
        return args == null ? List.of() : args.stream().map(this::toExpressionDTO).toList();
    }

    // === Old DTO → Dense ===

    public DenseQueryDto fromQueryDTO(QueryDTO dto) {
        if (dto == null) {
            return null;
        }
        return new DenseQueryDto(
            dto.from(),
            dto.fromAlias(),
            fromSelectorDTO(dto.selector()),
            dto.joins() == null ? null : dto.joins().stream()
                .map(this::fromJoinDTO)
                .collect(Collectors.toCollection(LinkedHashSet::new)),
            fromExpressionDTO(dto.where()),
            dto.groupBy() == null ? null : new GroupByDto(
                dto.groupBy().expressions().stream().map(this::fromExpressionDTO).toList()
            ),
            fromExpressionDTO(dto.having()),
            dto.orderBy() == null ? null : dto.orderBy().stream()
                .map(ob -> new OrderByDto(fromExpressionDTO(ob.expression()), ob.ascending()))
                .toList(),
            dto.limit(),
            dto.offset()
        );
    }

    DenseExpressionDto fromExpressionDTO(ExpressionDTO dto) {
        if (dto == null) {
            return null;
        }
        return switch (dto) {
            case PathDTO p -> DenseExpressionDto.path(p.path());
            case LiteralDTO l -> DenseExpressionDto.literal(l.value());
            case FunctionCallDTO f -> DenseExpressionDto.functionCall(
                f.functionName(),
                f.arguments().stream().map(this::fromExpressionDTO).toList()
            );
            case AggregationDTO a -> DenseExpressionDto.aggregation(
                a.functionName(),
                a.arguments().stream().map(this::fromExpressionDTO).toList(),
                a.distinct()
            );
            case BinaryExpressionDTO b -> DenseExpressionDto.binary(
                fromExpressionDTO(b.left()),
                b.operator().name(),
                fromExpressionDTO(b.right())
            );
            case UnaryExpressionDTO u -> DenseExpressionDto.unary(
                u.operator().name(),
                fromExpressionDTO(u.operand())
            );
            case TernaryExpressionDTO t -> DenseExpressionDto.ternary(
                fromExpressionDTO(t.first()),
                t.operator().name(),
                fromExpressionDTO(t.second()),
                fromExpressionDTO(t.third())
            );
            case WindowFunctionDTO w -> DenseExpressionDto.window(
                w.functionName(),
                w.arguments().stream().map(this::fromExpressionDTO).toList(),
                fromWindowSpecDTO(w.windowSpec())
            );
            case SubqueryDTO s -> DenseExpressionDto.subquery(fromQueryDTO(s.query()));
            case OuterRefDTO o -> DenseExpressionDto.outerRef(o.depth(), o.path());
        };
    }

    private DenseSelectorDto fromSelectorDTO(SelectorDTO dto) {
        if (dto == null) {
            return null;
        }
        return switch (dto) {
            case RootSelectorDTO r -> DenseSelectorDto.root(r.rootName(), r.distinct());
            case SingleExprSelectorDTO s -> DenseSelectorDto.single(
                fromExpressionDTO(s.expression()),
                s.distinct(),
                s.alias()
            );
            case MultiExprSelectorDTO m -> DenseSelectorDto.multi(
                m.expressions().stream()
                    .map(se -> new SelectedExpressionDto(fromExpressionDTO(se.expression()), se.alias()))
                    .collect(Collectors.toSet()),
                m.distinct()
            );
        };
    }

    private JoinDto fromJoinDTO(QueryDTO.JoinDTO dto) {
        return new JoinDto(
            new JoinedRootDto(dto.joinedRoot().rootName(), dto.joinedRoot().alias()),
            dto.joinType(),
            fromExpressionDTO(dto.onCondition())
        );
    }

    private WindowSpecDto fromWindowSpecDTO(QueryDTO.WindowSpecDTO dto) {
        if (dto == null) {
            return null;
        }
        return new WindowSpecDto(
            dto.partitionBy() == null ? null : dto.partitionBy().stream().map(this::fromExpressionDTO).toList(),
            dto.orderBy() == null ? null : dto.orderBy().stream()
                .map(ob -> new OrderByDto(fromExpressionDTO(ob.expression()), ob.ascending()))
                .toList()
        );
    }
}
