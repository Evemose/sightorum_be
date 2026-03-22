package com.rorm.ai.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "role")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ChatEntry.User.class, name = "user"),
    @JsonSubTypes.Type(value = ChatEntry.Assistant.class, name = "assistant"),
    @JsonSubTypes.Type(value = ChatEntry.System.class, name = "system"),
    @JsonSubTypes.Type(value = ChatEntry.ToolResult.class, name = "tool"),
    @JsonSubTypes.Type(value = ChatEntry.Thinking.class, name = "thinking"),
    @JsonSubTypes.Type(value = ChatEntry.ServerTool.class, name = "server_tool"),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface ChatEntry {

    record User(String content) implements ChatEntry {}

    record Assistant(String content, @Nullable List<ToolCallInfo> toolCalls) implements ChatEntry {}

    record System(String content) implements ChatEntry {}

    record ToolResult(List<ToolResponseInfo> responses) implements ChatEntry {}

    record Thinking(String content) implements ChatEntry {}

    record ServerTool(String toolName, @Nullable String input, @Nullable String output) implements ChatEntry {}

    record ToolCallInfo(String id, String type, String name, @Nullable String arguments) {}

    record ToolResponseInfo(String id, String name, @Nullable String data) {}
}
