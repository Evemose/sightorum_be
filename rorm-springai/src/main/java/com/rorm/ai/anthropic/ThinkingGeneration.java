package com.rorm.ai.anthropic;

import lombok.Getter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;

@Getter
public class ThinkingGeneration extends Generation {

    private final String thinkingText;

    public ThinkingGeneration(String thinkingText) {
        super(new AssistantMessage(""));
        this.thinkingText = thinkingText;
    }
}
