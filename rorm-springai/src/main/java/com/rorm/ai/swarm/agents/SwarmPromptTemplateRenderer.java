package com.rorm.ai.swarm.agents;

import com.rorm.ai.MetamodelContextBuilder;
import com.rorm.metamodel.ModelSpace;

import java.util.Map;

final class SwarmPromptTemplateRenderer {

    private static final String METAMODEL_PLACEHOLDER = "{{METAMODEL}}";

    private final String metamodelContext;

    SwarmPromptTemplateRenderer(ModelSpace modelSpace) {
        this.metamodelContext = new MetamodelContextBuilder().buildContext(modelSpace);
    }

    String render(String template) {
        return render(template, Map.of());
    }

    String render(String template, Map<String, String> placeholders) {
        var rendered = template.replace(METAMODEL_PLACEHOLDER, metamodelContext);
        for (var entry : placeholders.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }
}
