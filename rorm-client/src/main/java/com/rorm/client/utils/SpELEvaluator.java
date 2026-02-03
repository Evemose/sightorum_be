package com.rorm.client.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpELEvaluator {

    private final SpELParserUtils utils;

    public <T> T evaluate(String expression, Class<T> desiredResultType, Object rootObject) {
        var spelExpression = utils.getExpressionParser().parseExpression(expression);
        var context = utils.createEvaluationContext(rootObject);
        return spelExpression.getValue(context, desiredResultType);
    }

}
