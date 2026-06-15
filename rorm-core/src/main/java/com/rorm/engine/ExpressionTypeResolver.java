package com.rorm.engine;

import com.rorm.engine.handler.HandlerRegistry;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.metamodel.DataType.CategorcialType;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/**
 * Resolves the {@link DataType} of an {@link Expression} given a query context.
 * <p>
 * This component provides compile-time-like type information for expressions,
 * which can be used for validation, optimizations, and determining appropriate
 * data analysis operations.
 */
@Component
@RequiredArgsConstructor
public class ExpressionTypeResolver implements TypeResolutionContext {

    private final HandlerRegistry handlerRegistry;
    private Root currentFromRoot;

    @Override
    public Root fromRoot() {
        return currentFromRoot;
    }

    @Override
    public DataType resolve(Expression expression) {
        var resolved = resolveType(expression, currentFromRoot);
        return switch (resolved) {
            case ResolvedType.BasicType(var dt) -> dt;
            case ResolvedType.SingularReference(_, var idType) -> idType;
            case ResolvedType.PluralReference(_, var idType) -> new DataType.ListType(idType);
            case ResolvedType.Composite(var msg) -> throw new TypeResolutionException(msg);
        };
    }

    /**
     * Resolves the DataType of an expression within a query context.
     *
     * @param expression the expression to analyze
     * @param fromRoot   the query's FROM root (for path resolution context)
     * @return the resolved DataType, or null for NULL literals
     * @throws TypeResolutionException if type cannot be determined
     */
    @Nullable
    public DataType resolveWithRoot(Expression expression, Root fromRoot) {
        this.currentFromRoot = fromRoot;
        try {
            var resolved = resolveType(expression, fromRoot);
            return switch (resolved) {
                case ResolvedType.BasicType(var dt) -> dt;
                case ResolvedType.SingularReference(_, var idType) -> idType;
                case ResolvedType.PluralReference(_, var idType) -> new DataType.ListType(idType);
                case ResolvedType.Composite(var msg) -> throw new TypeResolutionException(msg);
            };
        } finally {
            this.currentFromRoot = null;
        }
    }

    /**
     * Resolves the full type information including reference metadata.
     *
     * @param expression the expression to analyze
     * @param fromRoot   the query's FROM root
     * @return the resolved type with full metadata
     */
    public ResolvedType resolveType(Expression expression, Root fromRoot) {
        return switch (expression) {
            case Path path -> resolvePathType(path);
            case Literal literal -> resolveLiteralType(literal);
            case FunctionCall func -> resolveFunctionType(func, fromRoot);
            case Aggregation agg -> resolveAggregationType(agg, fromRoot);
            case WindowFunction wf -> resolveWindowFunctionType(wf, fromRoot);
            case BinaryExpression bin -> resolveBinaryType(bin, fromRoot);
            case QuantifiedComparison _ -> new ResolvedType.BasicType(new DataType.BooleanType());
            case UnaryExpression un -> resolveUnaryType(un, fromRoot);
            case TernaryExpression ter -> resolveTernaryType(ter, fromRoot);
            case CaseExpression caseExpr -> resolveCaseType(caseExpr, fromRoot);
            case Subquery sub -> resolveSubqueryType(sub);
        };
    }

    private ResolvedType resolvePathType(Path path) {
        var target = path.target();
        return switch (target) {
            case BasicAttribute attr -> new ResolvedType.BasicType(attr.dataType());
            case BasicElement elem -> new ResolvedType.BasicType(elem.dataType());
            case SingularReferenceAttribute ref -> new ResolvedType.SingularReference(
                ref.targetRoot(),
                ref.targetRoot().idDescriptor().idAttribute().dataType()
            );
            case PluralReferenceAttribute ref -> new ResolvedType.PluralReference(
                ref.targetRoot(),
                ref.targetRoot().idDescriptor().idAttribute().dataType()
            );
            case CompositeAttribute _ -> new ResolvedType.Composite(
                "Composite attributes cannot be projected directly"
            );
            case CompositeElement _ -> new ResolvedType.Composite(
                "Composite elements cannot be projected directly"
            );
            case CollectionAttribute ca -> {
                var elementType = ca.elementType();
                yield switch (elementType) {
                    case BasicElement elem -> new ResolvedType.BasicType(
                        new DataType.ListType(elem.dataType())
                    );
                    case CompositeElement _ -> new ResolvedType.Composite(
                        "Composite collection elements cannot be projected directly"
                    );
                };
            }
            case AliasedRoot _ -> new ResolvedType.Composite(
                "AliasedRoot cannot be typed directly; select an attribute from it"
            );
        };
    }

    private ResolvedType resolveLiteralType(Literal literal) {
        var value = literal.value();
        if (value == null) {
            return new ResolvedType.BasicType(null);
        }

        var dataType = inferLiteralType(value);
        return new ResolvedType.BasicType(dataType);
    }

    private DataType inferLiteralType(Object value) {
        return switch (value) {
            case String _ -> new DataType.StringType();
            case Boolean _ -> new DataType.BooleanType();
            case Byte _, Short _, Integer _, Long _ -> new DataType.NumericType(19, 0);
            case Float _, Double _ -> new DataType.NumericType(15, 6);
            case BigDecimal bd -> new DataType.NumericType(bd.precision(), bd.scale());
            case LocalDate _ -> new DataType.DateType();
            case LocalTime _ -> new DataType.TimeType();
            case LocalDateTime _, Instant _ -> new DataType.DateTimeType();
            case ZonedDateTime _, OffsetDateTime _ -> new DataType.TimezoneType();
            case DayOfWeek _ -> new DataType.DayOfWeekType();
            case Enum<?> e -> new CategorcialType(getEnumValues(e.getClass()));
            case List<?> list -> inferListType(list);
            default -> throw new TypeResolutionException("Unknown literal type: " + value.getClass().getName());
        };
    }

    private DataType inferListType(List<?> list) {
        if (list.isEmpty()) {
            return new DataType.ListType(null);
        }
        var firstElement = list.getFirst();
        return new DataType.ListType(inferLiteralType(firstElement));
    }

    private String[] getEnumValues(Class<?> enumClass) {
        var constants = enumClass.getEnumConstants();
        var values = new String[constants.length];
        for (int i = 0; i < constants.length; i++) {
            values[i] = constants[i].toString();
        }
        return values;
    }

    private ResolvedType resolveFunctionType(FunctionCall func, Root fromRoot) {
        var handler = handlerRegistry.getFunction(func.functionName());
        var dataType = handler.resolveType(func.arguments(), this);
        return new ResolvedType.BasicType(dataType);
    }

    private ResolvedType resolveAggregationType(Aggregation agg, Root fromRoot) {
        var handler = handlerRegistry.getAggregation(agg.functionName());
        var dataType = handler.resolveType(agg.arguments(), this);
        return new ResolvedType.BasicType(dataType);
    }

    private ResolvedType resolveWindowFunctionType(WindowFunction wf, Root fromRoot) {
        var handler = handlerRegistry.getWindowFunction(wf.functionName());
        var dataType = handler.resolveType(wf.arguments(), this);
        return new ResolvedType.BasicType(dataType);
    }

    private ResolvedType resolveBinaryType(BinaryExpression bin, Root fromRoot) {
        var handler = handlerRegistry.getBinaryOperator(bin.operator());
        var dataType = handler.resolveType(bin.left(), bin.right(), this);
        return new ResolvedType.BasicType(dataType);
    }

    private ResolvedType resolveUnaryType(UnaryExpression un, Root fromRoot) {
        var handler = handlerRegistry.getUnaryOperator(un.operator());
        var dataType = handler.resolveType(un.operand(), this);
        return new ResolvedType.BasicType(dataType);
    }

    private ResolvedType resolveCaseType(CaseExpression caseExpr, Root fromRoot) {
        // Type of CASE is the type of the first THEN result
        return resolveType(caseExpr.whens().getFirst().result(), fromRoot);
    }

    private ResolvedType resolveTernaryType(TernaryExpression ter, Root fromRoot) {
        var handler = handlerRegistry.getTernaryOperator(ter.operator());
        var dataType = handler.resolveType(ter.first(), ter.second(), ter.third(), this);
        return new ResolvedType.BasicType(dataType);
    }

    private ResolvedType resolveSubqueryType(Subquery sub) {
        // For scalar subqueries, we'd need to analyze the selector
        var query = sub.query();
        var selector = query.selector();

        return switch (selector) {
            case Selector.SingleExprSelector(var expr, _, _) -> resolveType(expr, query.from().root());
            case Selector.MultiExprSelector _, Selector.RootSelector _ ->
                new ResolvedType.Composite("Non-scalar subquery cannot be typed");
        };
    }

    /**
     * Checks if the given path points to a reference attribute.
     *
     * @param path the path to check
     * @return true if the path target is a reference attribute
     */
    public boolean isReferenceType(Path path) {
        return path.target() instanceof ReferenceAttribute;
    }

    /**
     * Checks if the given path points to a singular reference (many-to-one or one-to-one).
     *
     * @param path the path to check
     * @return true if the path target is a singular reference
     */
    public boolean isSingularReference(Path path) {
        return path.target() instanceof SingularReferenceAttribute;
    }

    /**
     * Checks if the given path points to a plural reference (one-to-many or many-to-many).
     *
     * @param path the path to check
     * @return true if the path target is a plural reference
     */
    public boolean isPluralReference(Path path) {
        return path.target() instanceof PluralReferenceAttribute;
    }

    /**
     * Determines the appropriate TypeCategory for an expression.
     *
     * @param expression the expression to categorize
     * @param fromRoot   the query's FROM root
     * @return the category of the expression type
     */
    public TypeCategory categorize(Expression expression, Root fromRoot) {
        if (expression instanceof Path path && isReferenceType(path)) {
            return TypeCategory.REFERENCE;
        }
        var dataType = resolveWithRoot(expression, fromRoot);
        return TypeCategory.categorize(dataType);
    }

    /**
     * Represents the resolved type information for an expression.
     * For reference attributes, includes additional metadata about the reference.
     */
    public sealed interface ResolvedType {
        /**
         * A resolved basic data type.
         */
        record BasicType(DataType dataType) implements ResolvedType {}

        /**
         * A resolved singular reference type (many-to-one, one-to-one).
         */
        record SingularReference(Root targetRoot, DataType idType) implements ResolvedType {}

        /**
         * A resolved plural reference type (one-to-many, many-to-many).
         */
        record PluralReference(Root targetRoot, DataType idType) implements ResolvedType {}

        /**
         * A composite type that cannot be directly projected.
         */
        record Composite(String message) implements ResolvedType {}
    }
}
