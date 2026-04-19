package com.rorm.ai.chat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.rorm.ai.anthropic.ServerToolGeneration;
import com.rorm.ai.anthropic.StreamToolCallGeneration;
import com.rorm.ai.anthropic.ThinkingGeneration;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Typed streaming token. Consumers pattern-match on variants instead of parsing
 * string prefixes. Produced from {@link ChatResponse} via {@link #from(ChatResponse)},
 * which maps {@link Generation} subtypes to the corresponding variant.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "tokenType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = StreamToken.Text.class, name = "TEXT"),
    @JsonSubTypes.Type(value = StreamToken.Thinking.class, name = "THINKING"),
    @JsonSubTypes.Type(value = StreamToken.ToolCall.class, name = "TOOL_CALL"),
    @JsonSubTypes.Type(value = StreamToken.ServerTool.class, name = "SERVER_TOOL"),
    @JsonSubTypes.Type(value = StreamToken.SearchResult.class, name = "SEARCH_RESULT"),
    @JsonSubTypes.Type(value = StreamToken.Citation.class, name = "CITATION")
})
public sealed interface StreamToken {

    static StreamToken from(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return null;
        }
        var gen = response.getResult();
        if (gen instanceof ThinkingGeneration tg) {
            return new Thinking(tg.getThinkingText());
        }
        if (gen instanceof StreamToolCallGeneration stc) {
            return new ToolCall(stc.getToolName());
        }
        if (gen instanceof ServerToolGeneration stg) {
            var msg = stg.getServerToolMessage();
            return new ServerTool(msg.toolName(), msg.inputJson());
        }
        var msg = gen.getOutput();
        if (msg == null) {
            return null;
        }
        var text = msg.getText();
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (text.startsWith("[search_results]")) {
            return new SearchResult(text.substring("[search_results]".length()));
        }
        if (text.startsWith("[search_error]")) {
            return new SearchResult(text.substring("[search_error]".length()));
        }
        if (text.startsWith("[citation] ")) {
            var rest = text.substring("[citation] ".length());
            var dash = rest.indexOf(" — ");
            return dash >= 0
                ? new Citation(rest.substring(0, dash), rest.substring(dash + 3))
                : new Citation(rest, "");
        }
        return new Text(text);
    }

    String toText();

    record Text(String content) implements StreamToken {
        @Override
        public String toText() {
            return content;
        }
    }

    record Thinking(String content) implements StreamToken {
        @Override
        public String toText() {
            return content;
        }
    }

    record ToolCall(String name) implements StreamToken {
        @Override
        public String toText() {
            return "[tool_call] " + name;
        }
    }

    record ServerTool(String name, String query) implements StreamToken {
        @Override
        public String toText() {
            return query != null && !query.isEmpty() ? "[" + name + "] " + query : "[" + name + "]";
        }
    }

    record SearchResult(String content) implements StreamToken {
        @Override
        public String toText() {
            return "[search_results]" + content;
        }
    }

    record Citation(String url, String title) implements StreamToken {
        @Override
        public String toText() {
            return "[citation] " + url + (title != null && !title.isEmpty() ? " — " + title : "");
        }
    }
}
