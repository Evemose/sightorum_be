package com.rorm.ai;

import com.rorm.ai.chat.ChatProgress;
import com.rorm.metamodel.ModelSpace;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Typed wrapper for Spring AI ToolContext providing type-safe access to RORM-specific context data.
 */
@Slf4j
public record RormToolContext(
    ModelSpace modelSpace,
    @Nullable String schema
) {

    public RormToolContext(ModelSpace modelSpace) {
        this(modelSpace, null);
    }

    /**
     * Extract typed context from Spring AI's ToolContext.
     */
    public static RormToolContext from(ToolContext toolContext) {
        var context = toolContext.getContext();
        return new RormToolContext(
            (ModelSpace) context.get("modelSpace"),
            (String) context.get("schema")
        );
    }

    // TODO: Remove when ML module is refactored to use ModelSpace directly
    @Deprecated(forRemoval = true)
    public ChatProgress chatProgress() {
        log.warn("chatProgress() is deprecated - ML module should be refactored to use modelSpace() directly");
        return new ChatProgress(modelSpace);
    }

    /**
     * Convert this typed context to a Map for passing to Spring AI's toolContext() method.
     */
    public Map<String, Object> toMap() {
        var map = new HashMap<String, Object>();
        map.put("modelSpace", modelSpace);
        if (schema != null) {
            map.put("schema", schema);
        }
        return map;
    }
}
