package com.rorm.ai.swarm.agents;

import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.metamodel.ModelSpace;

import java.util.Map;

final class SwarmPromptTemplateRenderer {

    private final PromptPlaceholders placeholders;
    private final ModelSpace modelSpace;

    SwarmPromptTemplateRenderer(PromptPlaceholders placeholders, ModelSpace modelSpace) {
        this.placeholders = placeholders;
        this.modelSpace = modelSpace;
    }

    String render(String template) {
        return render(template, Map.of());
    }

    String render(String template, Map<String, String> extra) {
        var rendered = placeholders.resolve(template, modelSpace);
        for (var entry : extra.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }
}
