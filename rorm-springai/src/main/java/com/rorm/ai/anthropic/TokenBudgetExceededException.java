package com.rorm.ai.anthropic;

import lombok.Getter;

@Getter
public class TokenBudgetExceededException extends RuntimeException {

    private final String limitType;
    private final long retryAfterMs;

    public TokenBudgetExceededException(String limitType, long retryAfterMs, String message) {
        super(message);
        this.limitType = limitType;
        this.retryAfterMs = retryAfterMs;
    }
}
