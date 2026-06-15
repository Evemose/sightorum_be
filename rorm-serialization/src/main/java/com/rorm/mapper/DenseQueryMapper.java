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
        var dummyQuery = new QueryDTO(rootName, null, null, new LinkedHashSet<>(), null, null, null, null, null, null, null, null, null);
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
                .map(ob -> new QueryDTO.OrderByDTO(toExpressionDTO(ob.expression()), ob.ascending(), ob.nullsHandling()))
                .toList(),
            dense.limit(),
            dense.offset(),
            dense.withTies(),
            dense.ctes() == null ? null : dense.ctes().stream()
                .map(cte -> new QueryDTO.CteDTO(cte.name(), toQueryDTO(cte.query()), cte.columns()))
                .toList(),
            dense.setOperations() == null ? null : dense.setOperations().stream()
                .map(so -> new QueryDTO.SetOperationDTO(so.type(), toQueryDTO(so.query())))
                .toList()
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
                dense.distinct() != null && dense.distinct(),
                toExpressionDTO(dense.filterWhere())
            );
            case "binary" -> new BinaryExpressionDTO(
                toExpressionDTO(dense.left()),
                BinaryOperator.valueOf(dense.operator()),
                toExpressionDTO(dense.right())
            );
            case "quantified" -> new QuantifiedComparisonDTO(
                toExpressionDTO(dense.left()),
                BinaryOperator.valueOf(dense.operator()),
                QuantifierDTO.valueOf(dense.quantifier()),
                new SubqueryDTO(resolveQuantifiedQuery(dense))
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
            case "case" -> new ExpressionDTO.CaseExpressionDTO(
                dense.whens() == null ? List.of() : dense.whens().stream()
                    .map(w -> new ExpressionDTO.WhenClauseDTO(toExpressionDTO(w.condition()), toExpressionDTO(w.result())))
                    .toList(),
                toExpressionDTO(dense.elseExpr())
            );
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
                dense.expressions() == null ? new LinkedHashSet<>() : dense.expressions().stream()
                    .map(se -> new SelectedExpressionDTO(toExpressionDTO(se.expression()), se.alias()))
                                                                      .collect(Collectors.toCollection(LinkedHashSet::new)),
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
                .map(ob -> new QueryDTO.OrderByDTO(toExpressionDTO(ob.expression()), ob.ascending(), ob.nullsHandling()))
                .toList(),
            toWindowFrameDTO(dense.frame())
        );
    }

    private QueryDTO.WindowFrameDTO toWindowFrameDTO(WindowFrameDto dense) {
        if (dense == null) {
            return null;
        }
        return new QueryDTO.WindowFrameDTO(
            dense.type(),
            new QueryDTO.FrameBoundDTO(dense.start().type(), dense.start().offset()),
            new QueryDTO.FrameBoundDTO(dense.end().type(), dense.end().offset())
        );
    }

    private List<ExpressionDTO> mapArgs(List<DenseExpressionDto> args) {
        return args == null ? List.of() : args.stream().map(this::toExpressionDTO).toList();
    }

    private QueryDTO resolveQuantifiedQuery(DenseExpressionDto dense) {
        var query = dense.query();
        if (query != null) {
            return toQueryDTO(query);
        }
        var wrapped = dense.subquery();
        if (wrapped != null && "subquery".equals(wrapped.type()) && wrapped.query() != null) {
            return toQueryDTO(wrapped.query());
        }
        throw new IllegalArgumentException(
            "Quantified expression requires subquery query payload (provide query or subquery.query)"
        );
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
                .map(ob -> new OrderByDto(fromExpressionDTO(ob.expression()), ob.ascending(), ob.nullsHandling()))
                .toList(),
            dto.limit(),
            dto.offset(),
            dto.withTies(),
            dto.ctes() == null ? null : dto.ctes().stream()
                .map(cte -> new CteDto(cte.name(), fromQueryDTO(cte.query()), cte.columns()))
                .toList(),
            dto.setOperations() == null ? null : dto.setOperations().stream()
                .map(so -> new SetOperationDto(so.type(), fromQueryDTO(so.query())))
                .toList()
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
                a.distinct(),
                fromExpressionDTO(a.filterWhere())
            );
            case BinaryExpressionDTO b -> DenseExpressionDto.binary(
                fromExpressionDTO(b.left()),
                b.operator().name(),
                fromExpressionDTO(b.right())
            );
            case QuantifiedComparisonDTO q -> DenseExpressionDto.quantified(
                fromExpressionDTO(q.left()),
                q.comparison().name(),
                q.quantifier().name(),
                fromQueryDTO(q.subquery().query())
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
            case ExpressionDTO.CaseExpressionDTO c -> DenseExpressionDto.caseExpr(
                c.whens().stream()
                    .map(w -> new DenseExpressionDto.WhenClauseDto(fromExpressionDTO(w.condition()), fromExpressionDTO(w.result())))
                    .toList(),
                fromExpressionDTO(c.elseExpr())
            );
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
                .map(ob -> new OrderByDto(fromExpressionDTO(ob.expression()), ob.ascending(), ob.nullsHandling()))
                .toList(),
            fromWindowFrameDTO(dto.frame())
        );
    }

    private WindowFrameDto fromWindowFrameDTO(QueryDTO.WindowFrameDTO dto) {
        if (dto == null) {
            return null;
        }
        return new WindowFrameDto(
            dto.type(),
            new FrameBoundDto(dto.start().type(), dto.start().offset()),
            new FrameBoundDto(dto.end().type(), dto.end().offset())
        );
    }
}
