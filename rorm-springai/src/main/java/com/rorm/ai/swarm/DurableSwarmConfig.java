package com.rorm.ai.swarm;

import com.rorm.ai.anthropic.AnthropicChatOptions.CacheTTL;
import com.rorm.ai.chat.CacheStrategy;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.chat.ToolGroup;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.springframework.util.StringUtils.hasText;

@ConfigurationProperties(prefix = "rorm.ai.durable-swarm")
public record DurableSwarmConfig(
    AgentModelConfig scout,
    AgentModelConfig domainResearcher,
    AgentModelConfig generator,
    String rebuttalPromptTemplate,
    AgentModelConfig mechanicalSceptic,
    AgentModelConfig executorCompiler,
    AgentModelConfig forensicPathologist,
    AgentModelConfig summarizer
) {

    private static final String PROMPT_ROOT = "prompts/durable-swarm/";

    private static final CacheStrategy SHORT_CACHE = _ -> CacheTTL.SHORT;
    private static final CacheStrategy NO_CACHE = _ -> CacheTTL.NONE;
    private static final CacheStrategy FIRST_ROUND_SHORT = ctx ->
        ctx.previousRounds().isEmpty() ? CacheTTL.SHORT : CacheTTL.NONE;

    public DurableSwarmConfig {
        scout = withDefaults(scout, "scout", ThinkingLevel.HIGH,
            Set.of(ToolGroup.QUERY), SHORT_CACHE).withModel("claude-sonnet-4-6");
        domainResearcher = withDefaults(domainResearcher, "domain-researcher", ThinkingLevel.HIGH,
            Set.of(ToolGroup.WEB_ACCESS), NO_CACHE);
        generator = withDefaults(generator, "generator", ThinkingLevel.HIGH,
            Set.of(ToolGroup.QUERY, ToolGroup.DATA_RELATIONS), FIRST_ROUND_SHORT);
        if (!hasText(rebuttalPromptTemplate)) {
            rebuttalPromptTemplate = loadResourceIfExists(PROMPT_ROOT + "rebuttal-user.txt");
        }
        mechanicalSceptic = withDefaults(mechanicalSceptic, "mechanical-sceptic", ThinkingLevel.HIGH,
            Set.of(ToolGroup.QUERY, ToolGroup.VERIFICATION), SHORT_CACHE);
        executorCompiler = withDefaults(executorCompiler, "executor-compiler", ThinkingLevel.HIGH,
            Set.of(ToolGroup.QUERY), SHORT_CACHE);
        forensicPathologist = withDefaults(forensicPathologist, "forensic-pathologist", ThinkingLevel.HIGH,
            Set.of(), NO_CACHE);
        summarizer = withDefaults(summarizer, "summarizer", ThinkingLevel.NONE,
            Set.of(), NO_CACHE).withModel("claude-haiku-4-5");
    }

    private static AgentModelConfig withDefaults(AgentModelConfig config, String name,
                                                 ThinkingLevel defaultThinking,
                                                 Set<ToolGroup> defaultToolGroups,
                                                 CacheStrategy defaultCacheStrategy) {
        if (config == null) {
            config = new AgentModelConfig(null, null, null, ThinkingLevel.NONE, null, null);
        }
        if (!hasText(config.systemPrompt())) {
            config = config.withSystemPrompt(loadResource(PROMPT_ROOT + name + "-system.txt"));
        }
        if (!hasText(config.userPromptTemplate())) {
            var userTemplate = loadResourceIfExists(PROMPT_ROOT + name + "-user.txt");
            if (userTemplate != null) {
                config = config.withUserPromptTemplate(userTemplate);
            }
        }
        if (config.thinkingLevel() == ThinkingLevel.NONE && defaultThinking != ThinkingLevel.NONE) {
            config = config.withThinkingLevel(defaultThinking);
        }
        if (config.toolGroups() == null) {
            config = config.withToolGroups(defaultToolGroups);
        }
        if (config.cacheStrategy() == null) {
            config = config.withCacheStrategy(defaultCacheStrategy);
        }
        if (config.model() == null) {
            config = config.withModel("claude-opus-4-6");
        }
        return config;
    }

    private static String loadResourceIfExists(String path) {
        var resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load prompt resource: " + path, e);
        }
    }

    private static String loadResource(String path) {
        var resource = new ClassPathResource(path);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing default prompt resource: " + path, e);
        }
    }
}
