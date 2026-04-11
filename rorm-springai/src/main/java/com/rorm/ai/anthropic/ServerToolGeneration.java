package com.rorm.ai.anthropic;

import com.rorm.ai.chat.ServerToolMessage;
import lombok.Getter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;

@Getter
public class ServerToolGeneration extends Generation {

    private final ServerToolMessage serverToolMessage;

    public ServerToolGeneration(ServerToolMessage serverToolMessage) {
        super(new AssistantMessage(""));
        this.serverToolMessage = serverToolMessage;
    }
}
