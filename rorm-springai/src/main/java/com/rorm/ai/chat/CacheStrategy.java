package com.rorm.ai.chat;

import com.rorm.ai.anthropic.AnthropicChatOptions;
import com.rorm.ai.anthropic.AnthropicChatOptions.CacheTTL;

@FunctionalInterface
public interface CacheStrategy {

    CacheTTL strategy(AnthropicChatOptions.RoundContext roundContext);

}
