package com.rorm.mapper;

import com.rorm.dto.ExpressionDTO;
import com.rorm.dto.QueryDTO;
import com.rorm.metamodel.ModelSpace;
import com.rorm.query.Expression;
import lombok.RequiredArgsConstructor;

/**
 * Standalone mapper for Expression <-> ExpressionDTO conversion.
 * Delegates to QueryMapper for actual mapping logic.
 */
@RequiredArgsConstructor
public class ExpressionMapper {

    private final QueryMapper queryMapper;

    public ExpressionDTO toDTO(Expression expression) {
        return queryMapper.toDTO(expression);
    }

    public Expression toEntity(ExpressionDTO dto, ModelSpace modelSpace, QueryDTO query) {
        return queryMapper.toEntity(dto, modelSpace, query);
    }
}
