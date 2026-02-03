package com.rorm.ai;

import com.rorm.ai.chat.ChatProgress;
import com.rorm.metamodel.ModelSpace;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

/**
 * Typed wrapper for Spring AI ToolContext providing type-safe access to RORM-specific context data.
 * This record encapsulates the ChatProgress and ModelSpace that tools need during execution.
 */
public record RormToolContext(ChatProgress chatProgress) {

    /**
     * Extract typed context from Spring AI's ToolContext.
     *
     * @throws ClassCastException   if context doesn't contain expected types
     * @throws NullPointerException if required context keys are missing
     */
    public static RormToolContext from(ToolContext toolContext) {
        var context = toolContext.getContext();
        return new RormToolContext(
            (ChatProgress) context.get("chatProgress")
        );
    }

    /**
     * Convert this typed context to a Map for passing to Spring AI's toolContext() method.
     */
    public Map<String, Object> toMap() {
        return Map.of(
            "chatProgress", chatProgress
        );
    }

    public ModelSpace modelSpace() {
        return chatProgress.getModelSpace();
    }
}
