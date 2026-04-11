package com.rorm.ai.anthropic;

import lombok.Getter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;

/**
 * Streaming-only marker emitted by
 * {@link JournaledAnthropicChatModel}'s {@code toolCallChunk} when a tool-use
 * content block starts during streaming. Carries the tool name for live
 * visualization via {@link com.rorm.ai.chat.StreamToken} but exposes no
 * text and no {@code toolCalls} on its {@link AssistantMessage} output, so
 * Spring AI's {@code ChatClientMessageAggregator} cannot accidentally
 * aggregate it into the final {@code AssistantMessage} — which would
 * otherwise persist an empty-id tool call into chat memory.
 * <p>
 * The authoritative tool-call record for memory persistence is
 * {@link ToolRoundGeneration}, built once at the end of the stream.
 */
@Getter
public class StreamToolCallGeneration extends Generation {

    private final String toolName;

    public StreamToolCallGeneration(String toolName) {
        super(new AssistantMessage(""));
        this.toolName = toolName;
    }
}
