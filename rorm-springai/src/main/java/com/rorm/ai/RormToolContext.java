package com.rorm.ai;

import com.rorm.StepJournal;
import com.rorm.metamodel.ModelSpace;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Typed wrapper for Spring AI ToolContext providing type-safe access to RORM-specific context data.
 */
public record RormToolContext(
    ModelSpace modelSpace,
    String schema,
    StepJournal stepJournal,
    @Nullable String id
) {

    /**
     * Extract typed context from Spring AI's ToolContext.
     */
    public static RormToolContext from(ToolContext toolContext) {
        var context = toolContext.getContext();
        return new RormToolContext(
            (ModelSpace) context.get("modelSpace"),
            (String) context.get("schema"),
            (StepJournal) context.get("stepJournal"),
            (String) context.get("id")
        );
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
        if (stepJournal != null) {
            map.put("stepJournal", stepJournal);
        }
        if (id != null) {
            map.put("id", id);
        }
        return map;
    }
}
