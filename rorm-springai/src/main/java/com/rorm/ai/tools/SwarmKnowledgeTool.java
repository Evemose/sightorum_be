package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.JournaledTool;
import com.rorm.ai.swarm.communication.SwarmToolContext;
import com.rorm.ai.swarm.knowledge.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Tool callbacks that expose the {@link SwarmKnowledgeStore} to swarm
 * agents. Provides a record-and-search pair with explicit scope so an
 * agent can choose between scratch memory shared only with rivals in the
 * current run and persistent dataset memory that survives across runs.
 */
@Slf4j
@Component
@JournaledTool
@RequiredArgsConstructor
public class SwarmKnowledgeTool {

    private final SwarmKnowledgeStore knowledgeStore;
    private final ObjectMapper objectMapper;

    @Tool(
        name = "recordKnowledge",
        description = """
            Persist a piece of knowledge produced during this run. Use this
            for findings, observations, methodological notes, or counter-
            evidence you want yourself or future agents to be able to recall.
            
            scope=RUN: memory shared only with other agents in the current run;
              cleared when the run completes.
            scope=DATASET: persistent memory associated with the dataset; future
              swarm runs against this same schema can recall it.
            scope=BOTH: write to both — useful for findings worth carrying
              forward but also relevant right now.
            """
    )
    public String recordKnowledge(
        @ToolParam(description = "Knowledge entry to persist") RecordKnowledgeInput input,
        ToolContext toolContext
    ) {
        var ctx = SwarmToolContext.from(toolContext);
        var entry = new KnowledgeEntry(
            ctx.runId(), ctx.base().schema(), ctx.askerRole(),
            input.content(), input.kind(), input.scope().toScopes(), Map.of());
        var ref = knowledgeStore.store(entry);
        log.debug("[swarm-knowledge] {} stored {} into {}",
            ctx.askerRole(), input.kind(), ref.storedIn());
        return write(ref);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize knowledge tool response", e);
        }
    }

    @Tool(
        name = "searchKnowledge",
        description = """
            Semantic search over the swarm knowledge store. Returns at most
            topK matches ordered by similarity. Use this before duplicating
            research, to recall what other agents have observed, or to find
            counter-evidence flagged in earlier runs.
            """
    )
    public String searchKnowledge(
        @ToolParam(description = "Search query and scope") SearchKnowledgeInput input,
        ToolContext toolContext
    ) {
        var ctx = SwarmToolContext.from(toolContext);
        var request = new KnowledgeSearchRequest(
            ctx.runId(), ctx.base().schema(), input.query(),
            input.scope().toScopes(), input.topK());
        return write(knowledgeStore.search(request));
    }

    public enum ScopeChoice {
        RUN, DATASET, BOTH;

        Set<KnowledgeScope> toScopes() {
            return switch (this) {
                case RUN -> EnumSet.of(KnowledgeScope.RUN);
                case DATASET -> EnumSet.of(KnowledgeScope.DATASET);
                case BOTH -> EnumSet.of(KnowledgeScope.RUN, KnowledgeScope.DATASET);
            };
        }
    }

    public record RecordKnowledgeInput(
        @ToolParam(description = "The text to store. One self-contained claim is preferred.") String content,
        @ToolParam(description = "Intent class: RESEARCH, OBSERVATION, FINDING, COUNTER_EVIDENCE, METHOD_NOTE") KnowledgeKind kind,
        @ToolParam(description = "Where to store: RUN, DATASET, or BOTH") ScopeChoice scope
    ) {
    }

    public record SearchKnowledgeInput(
        @ToolParam(description = "Natural-language search query") String query,
        @ToolParam(description = "Where to search: RUN, DATASET, or BOTH") ScopeChoice scope,
        @ToolParam(description = "Maximum number of matches to return") int topK
    ) {
    }
}
