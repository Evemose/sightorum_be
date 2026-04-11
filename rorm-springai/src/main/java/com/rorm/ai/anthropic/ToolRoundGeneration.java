package com.rorm.ai.anthropic;

import lombok.Getter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.Generation;

@Getter
public class ToolRoundGeneration extends Generation {

    private final ToolResponseMessage toolResponse;

    public ToolRoundGeneration(AssistantMessage withToolCalls, ToolResponseMessage toolResponse) {
        super(withToolCalls);
        this.toolResponse = toolResponse;
    }
}
