package com.rorm.client.research.rewind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.anthropic.AnthropicChatOptions.CacheTTL;
import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.client.chat.session.SessionAnalysis;
import com.rorm.metamodel.ModelSpace;
import com.rorm.viz.dto.chart.ChartBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Composes the rewind narration via the project's typed AI chat
 * service, one call per section.
 *
 * <p>Three call shapes:
 * <ul>
 *   <li>{@link #composeRecon} — recon scout + domain transcripts in,
 *       a {@link RewindDTO.ReconSlide} out.</li>
 *   <li>{@link #composeChain} — one anchor / hypothesis bundle in,
 *       a {@link RewindDTO.HypothesisChain} out (a single call
 *       produces all four slides of the chain at once so the prose
 *       within the chain stays coherent).</li>
 *   <li>{@link #composeVerdict} — the judge's prose in, a
 *       {@link RewindDTO.VerdictSlides} out.</li>
 * </ul>
 *
 * <p>All section calls fan out concurrently on a virtual-thread
 * executor: recon, every hypothesis chain, and the verdict run in
 * parallel. With per-call latency on the order of a few seconds, the
 * wall-clock cost of a typical 12-hypothesis rewind matches the
 * slowest single call rather than their sum.
 *
 * <p>If any single section fails (timeout, malformed JSON, etc.),
 * {@link #composeAll} falls back to a deterministic placeholder for
 * that section so the rewind still renders end-to-end. The placeholder
 * mirrors the narrator's earlier behaviour: first sentence from the
 * relevant transcript with manager-tone scaffolding.
 *
 * <p>Calls go through {@link AiChatService} so they pick up retry,
 * model fallback, structured-output conversion, journaling and the
 * project's standard {@code AnthropicChatOptions} wiring — no raw
 * {@code ChatClient} use, no manual code-fence stripping, no manual
 * translation tables.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class RewindNarrator {

    private final AiChatService chatService;
    private final ObjectMapper objectMapper;

    @Value("${rorm.rewind.model:claude-sonnet-4-6}")
    private String modelName;

    /**
     * The Narrator voice — lifted from the running swarm's
     * {@code progress-paraphraser-system.txt} (the same prompt that
     * authors every {@code agent_progress} line shown in the DAG
     * during a run) so the rewind reads as a continuation of that
     * voice rather than a parallel one. We keep only the {@code <role>}
     * block from that prompt — the round-by-round mechanics
     * ({@code <inputs>}, {@code <output_rules>}, {@code <character>}
     * placeholder) don't apply to a one-shot post-run recap — and
     * re-frame the audience accordingly. Loaded once at class-load
     * via a classpath read; falls back to a compact inline voice if
     * the resource is unavailable.
     */
    // ROLE_BLOCK is declared BEFORE the constants that use it — static
    // fields initialise in source order, so `loadVoice()` running first
    // would see a null pattern.
    private static final Pattern ROLE_BLOCK = Pattern.compile(
        "<role>\\s*(.*?)\\s*</role>", Pattern.DOTALL);

    private static final String VOICE = loadVoice();
    private static final String RECON_SYSTEM   = renderTemplate("prompts/rewind/recon-system.txt");
    private static final String CHAIN_SYSTEM   = renderTemplate("prompts/rewind/chain-system.txt");
    private static final String VERDICT_SYSTEM = renderTemplate("prompts/rewind/verdict-system.txt");

    /**
     * Pulls just the {@code <role>} block out of the running swarm's
     * {@code progress-paraphraser-system.txt} so the rewind reads as
     * a continuation of the same Narrator voice the viewer saw during
     * the run. Only the role block — the prompt's round-by-round
     * mechanics (inputs / output_rules / character placeholder) don't
     * apply to a one-shot post-run recap.
     */
    private static String loadVoice() {
        var resource = new ClassPathResource("prompts/durable-swarm/progress-paraphraser-system.txt");
        if (!resource.exists()) return "";
        try {
            var raw = resource.getContentAsString(StandardCharsets.UTF_8);
            var m = ROLE_BLOCK.matcher(raw);
            return m.find() ? m.group(1).trim() : "";
        } catch (IOException e) {
            log.warn("[rewind] failed to load narrator voice: {}", e.toString());
            return "";
        }
    }

    /**
     * Loads a rewind system-prompt template and substitutes
     * {@code {{VOICE}}} with the narrator role block. The prompts
     * themselves live as classpath resources alongside every other
     * agent prompt in the project — keeping them out of Java string
     * literals so they can be reviewed, diffed, and edited without a
     * recompile.
     */
    private static String renderTemplate(String path) {
        var resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("Missing rewind prompt resource: " + path);
        }
        try {
            var raw = resource.getContentAsString(StandardCharsets.UTF_8);
            return raw.replace("{{VOICE}}", VOICE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load rewind prompt " + path, e);
        }
    }

    /** Decoded agent transcript. Carries the raw response text and
     *  the structured output (when present), plus the tags that
     *  group agents into anchors / hypotheses. */
    record AgentTranscript(
        String token,
        String kind,
        Map<String, String> tags,
        String rawResponse,
        @Nullable JsonNode output
    ) {
        AgentTranscript withResolution(String raw, @Nullable JsonNode out) {
            return new AgentTranscript(token, kind, tags, raw, out);
        }
    }

    // ─── Entry point ──────────────────────────────────────────────

    RewindDTO.ReconSlide composeRecon(
            SessionAnalysis analysis,
            Map<String, AgentTranscript> agents,
            String schema,
            ModelSpace modelSpace) {
        var asList = new ArrayList<>(agents.values());
        var scouts  = pickAll(asList, "scout");
        var domains = pickAll(asList, "domain", "domain-researcher");
        var query   = Optional.ofNullable(analysis.getQuery()).orElse("an open-ended causal question");
        var user = """
            QUESTION ASKED OF THE APPARATUS
            ===============================
            %s

            SCOPE CHIPS (factual; reuse verbatim as the `scope` field, joined with " · ")
            ===========
            %s

            SCOUT ITERATIONS (in order; full structured output + prose)
            ================
            %s

            DOMAIN RESEARCHER ITERATIONS (in order; full structured output + prose)
            ============================
            %s
            """.formatted(
                query,
                scopeChips(agents),
                renderIterations("scout",  scouts,  6000),
                renderIterations("domain", domains, 6000));

        try {
            var draft = call("recon", RECON_SYSTEM, user, ReconDraft.class, schema, modelSpace);
            return new RewindDTO.ReconSlide(
                draft.headline(),
                draft.scope(),
                draft.framing(),
                draft.hopedFor(),
                elaborateOf(scouts, domains));
        } catch (Exception e) {
            log.warn("[rewind] recon narration failed, using fallback: {}", e.toString());
            return placeholderRecon(analysis, agents,
                lastOrNull(scouts), lastOrNull(domains));
        }
    }

    // ─── Chain ────────────────────────────────────────────────────

    RewindDTO.HypothesisChain composeChain(
            String hypothesisName,
            List<AgentTranscript> agents,
            List<String> peerHypothesisLabels,
            String schema,
            ModelSpace modelSpace) {
        var compilers   = pickAll(agents, "compiler");
        var sceptics    = pickAll(agents, "compiler-sceptic", "sceptic");
        var forensics   = pickAll(agents, "forensic-pathologist", "forensic");
        var advocates   = pickAll(agents, "advocate");
        var prosecutors = pickAll(agents, "prosecutor");
        var supervisors = pickAll(agents, "supervisor", "rebuttal");
        var anchor = agents.stream()
            .map(a -> a.tags.get("anchor"))
            .filter(s -> s != null && !s.isBlank())
            .findFirst()
            .orElse(hypothesisName);

        // Build a single transcript block per role that DUMPS every
        // iteration with an explicit iter index, so the narrator sees
        // the full LOOP_TO_… → re-run → PASS_THROUGH progression
        // rather than just the first verdict. The supervisor block is
        // the load-bearing one for the outcome slide — its terminal
        // entry's `decision` field is the truth, not any earlier
        // loopback prose.
        var user = """
            HYPOTHESIS IDENTITY (for context only — do not echo)
            ===================
            anchor: %s
            hypothesis: %s

            PEER HYPOTHESES IN THIS RUN (for distinctness — do not echo)
            ===========================
            %s

            COMPILER ITERATIONS (in order; each is a full run with the compiler responding to previous supervisor feedback)
            ===================
            %s

            SCEPTIC ITERATIONS (in order; pairs with compiler iterations above)
            ==================
            %s

            SUPERVISOR ITERATIONS (in order; the TERMINAL entry's `decision` is the binding verdict)
            =====================
            %s

            FORENSIC PATHOLOGIST (post-iteration; key numbers + classification)
            ====================
            %s

            ADVOCATE (after iterations completed)
            ========
            %s

            PROSECUTOR (after iterations completed)
            ==========
            %s
            """.formatted(
                anchor, hypothesisName,
                renderPeers(peerHypothesisLabels),
                renderIterations("compiler", compilers, 3500),
                renderIterations("sceptic", sceptics, 3500),
                renderIterations("supervisor", supervisors, 3500),
                renderIterations("forensic", forensics, 4000),
                renderIterations("advocate", advocates, 3500),
                renderIterations("prosecutor", prosecutors, 3500));

        try {
            var draft = call("chain", CHAIN_SYSTEM, user, ChainDraft.class, schema, modelSpace);
            // Elaborate panel concatenates every iteration's prose so
            // the curious user can dig into the actual transcripts.
            return new RewindDTO.HypothesisChain(
                anchor,
                hypothesisName,
                new RewindDTO.InitialProposal(
                    draft.initialHeadline(),
                    draft.initialBody(),
                    parseVisual(draft.initialVisual()),
                    elaborateOf(compilers)),
                new RewindDTO.RefinedProposal(
                    draft.refinedHeadline(),
                    draft.refinedBody(),
                    draft.refinedWhatChanged(),
                    parseVisual(draft.refinedVisual()),
                    elaborateOf(sceptics, forensics, supervisors)),
                new RewindDTO.Verification(
                    draft.verificationHeadline(),
                    draft.verificationBody(),
                    nonEmptyList(draft.verificationChallenges()),
                    nonEmptyList(draft.verificationHeld()),
                    elaborateOf(advocates, prosecutors)),
                new RewindDTO.Outcome(
                    parseVerdict(draft.outcomeVerdict()),
                    draft.outcomeHeadline(),
                    draft.outcomeBody(),
                    draft.outcomeCaveat(),
                    elaborateOf(supervisors),
                    draft.outcomeCharts()));
        } catch (Exception e) {
            log.warn("[rewind] chain {} narration failed, using fallback: {}",
                hypothesisName, e.toString());
            return placeholderChain(hypothesisName, anchor,
                lastOrNull(compilers), lastOrNull(sceptics), lastOrNull(forensics),
                lastOrNull(advocates), lastOrNull(prosecutors), lastOrNull(supervisors));
        }
    }

    // ─── Verdict ──────────────────────────────────────────────────

    RewindDTO.VerdictSlides composeVerdict(
            Map<String, AgentTranscript> agents,
            String schema,
            ModelSpace modelSpace) {
        final var judges = pickAll(new ArrayList<>(agents.values()), "judge");
        var anchors = anchorList(agents);
        var hypotheses = hypothesisList(agents);
        var user = """
            JUDGE ITERATIONS (in order; the TERMINAL entry is binding)
            ================
            %s

            ANCHORS INVESTIGATED
            ====================
            %s

            HYPOTHESES INVESTIGATED
            =======================
            %s
            """.formatted(
                renderIterations("judge", judges, 8000),
                String.join("\n", anchors.stream().map(s -> "- " + s).toList()),
                String.join("\n", hypotheses.stream().map(s -> "- " + s).toList()));

        try {
            var draft = call("verdict", VERDICT_SYSTEM, user, VerdictDraft.class, schema, modelSpace);
            return new RewindDTO.VerdictSlides(
                new RewindDTO.VerdictSlides.BottomLine(
                    draft.bottomLineHeadline(),
                    draft.bottomLineBody(),
                    draft.bottomLineCharts()),
                new RewindDTO.VerdictSlides.Pattern(
                    draft.patternHeadline(),
                    draft.patternBody(),
                    draft.patternCrosscut(),
                    draft.patternCharts()),
                new RewindDTO.VerdictSlides.Caveats(
                    draft.caveatsHeadline(),
                    draft.caveatsBody()));
        } catch (Exception e) {
            log.warn("[rewind] verdict narration failed, using fallback: {}", e.toString());
            return placeholderVerdict(lastOrNull(judges), agents);
        }
    }

    // ─── Orchestrator entry point ─────────────────────────────────

    /**
     * Run every section concurrently and assemble the DTO. Uses an
     * ephemeral virtual-thread executor so each call gets its own
     * carrier without saturating any global pool. Total wall-clock
     * ~= slowest section, not the sum.
     */
    record RewindCompose(
        RewindDTO.ReconSlide recon,
        List<RewindDTO.HypothesisChain> chains,
        RewindDTO.VerdictSlides verdict
    ) {}

    RewindCompose composeAll(
            SessionAnalysis analysis,
            Map<String, AgentTranscript> agents,
            String schema,
            ModelSpace modelSpace) {
        var byHypothesis = groupByHypothesis(agents);
        // Peer labels: the names of all OTHER hypotheses, given to each
        // chain prompt so the narrator can articulate what makes the
        // current one distinct rather than producing N lookalike arcs.
        // Names alone are deliberately bare — the narrator should not
        // start cross-comparing details it can't see.
        var hypothesisNames = new ArrayList<>(byHypothesis.keySet());
        try (var executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("rewind-sec-", 0).factory())) {
            var reconF   = CompletableFuture.supplyAsync(
                () -> composeRecon(analysis, agents, schema, modelSpace), executor);
            var verdictF = CompletableFuture.supplyAsync(
                () -> composeVerdict(agents, schema, modelSpace), executor);
            var chainFs  = new ArrayList<CompletableFuture<RewindDTO.HypothesisChain>>();
            for (var entry : byHypothesis.entrySet()) {
                final var name  = entry.getKey();
                final var group = entry.getValue();
                final var peers = hypothesisNames.stream()
                    .filter(n -> !n.equals(name))
                    .toList();
                chainFs.add(CompletableFuture.supplyAsync(
                    () -> composeChain(name, group, peers, schema, modelSpace), executor));
            }
            // Bound the total wall-clock to keep the rewind tab from
            // hanging if the model stalls. Five minutes is generous
            // for 15-ish parallel calls; on success it'll be ~10-30s.
            var all = CompletableFuture.allOf(
                java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(reconF, verdictF),
                    chainFs.stream()
                ).toArray(CompletableFuture[]::new));
            try {
                all.get(12, TimeUnit.MINUTES);
            } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
                log.warn("[rewind] one or more sections timed out / failed: {}", e.toString());
                // Continue with whatever resolved; the per-section
                // try/catch above means failed sections already have
                // a placeholder result.
            }
            var chains = new ArrayList<RewindDTO.HypothesisChain>();
            for (var f : chainFs) {
                try { chains.add(f.get(1, TimeUnit.SECONDS)); }
                catch (Exception ignored) { /* dropped — see warning above */ }
            }
            var recon = safeJoin(reconF,
                () -> placeholderRecon(analysis, agents,
                    findOne(agents, "scout").orElse(null),
                    findOne(agents, "domain").orElse(null)));
            var verdict = safeJoin(verdictF,
                () -> placeholderVerdict(findOne(agents, "judge").orElse(null), agents));
            return new RewindCompose(recon, chains, verdict);
        }
    }

    private <T> T safeJoin(CompletableFuture<T> f, java.util.function.Supplier<T> fallback) {
        try { return f.get(1, TimeUnit.SECONDS); }
        catch (Exception e) { return fallback.get(); }
    }

    // ─── Typed chat call ──────────────────────────────────────────

    /**
     * Issue a typed chat request against {@link AiChatService}. The service
     * handles structured-output conversion via {@code BeanOutputConverter},
     * code-fence tolerance, retry on malformed JSON, and the journaling /
     * tool-context wiring — we just hand it a system prompt, a user prompt,
     * and the target type.
     *
     * <p>Caching strategy is forced to {@link CacheTTL#NONE} because each
     * rewind section is a one-shot recap whose user prompt is unique per
     * run (it embeds the run's specific transcripts) — there is no
     * cross-call reuse to amortise a cache breakpoint against.
     */
    private <T> T call(
            String section,
            String systemPrompt,
            String userPrompt,
            Class<T> responseType,
            String schema,
            ModelSpace modelSpace) {
        var request = ChatRequest.usingData(schema, modelSpace)
            .withModelName(modelName)
            .withSystemPrompt(systemPrompt)
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .withCachingStrategyFunction(_ -> CacheTTL.NONE)
            .ask(userPrompt, responseType);
        var result = chatService.call(request);
        if (result == null) {
            throw new IllegalStateException("Empty rewind narration for section " + section);
        }
        return result;
    }

    // ─── Response shapes ──────────────────────────────────────────

    /** What the model returns for the recon slide. Fields map 1-to-1
     *  to the FE-visible {@link RewindDTO.ReconSlide}. */
    public record ReconDraft(
        String headline,
        String scope,
        String framing,
        String hopedFor
    ) {}

    /** What the model returns for one hypothesis chain. All four
     *  slides' content is generated in one call so the per-slide
     *  prose stays coherent (and we use only 1 round trip per
     *  hypothesis instead of 4). */
    public record ChainDraft(
        String initialHeadline,
        String initialBody,
        String initialVisual,

        String refinedHeadline,
        String refinedBody,
        String refinedWhatChanged,
        String refinedVisual,

        String verificationHeadline,
        String verificationBody,
        List<String> verificationChallenges,
        List<String> verificationHeld,

        String outcomeVerdict,
        String outcomeHeadline,
        String outcomeBody,
        @Nullable String outcomeCaveat,
        // 1-2 charts for the outcome slide. Strongly preferred — see
        // the chain prompt. Empty list is the escape hatch when no
        // numeric finding exists in the transcripts.
        List<ChartBlock> outcomeCharts
    ) {}

    public record VerdictDraft(
        String bottomLineHeadline,
        String bottomLineBody,
        @Nullable List<ChartBlock> bottomLineCharts,

        String patternHeadline,
        String patternBody,
        String patternCrosscut,
        @Nullable List<ChartBlock> patternCharts,

        String caveatsHeadline,
        String caveatsBody
    ) {}

    // ─── Parsers / normalisers ────────────────────────────────────

    private static RewindDTO.Visual parseVisual(@Nullable String raw) {
        if (raw == null) return RewindDTO.Visual.arrow;
        try { return RewindDTO.Visual.valueOf(raw.trim().toLowerCase()); }
        catch (IllegalArgumentException ex) { return RewindDTO.Visual.arrow; }
    }

    private static RewindDTO.VerdictTag parseVerdict(@Nullable String raw) {
        if (raw == null) return RewindDTO.VerdictTag.INCONCLUSIVE;
        try { return RewindDTO.VerdictTag.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException ex) { return RewindDTO.VerdictTag.INCONCLUSIVE; }
    }

    private static List<String> nonEmptyList(@Nullable List<String> xs) {
        if (xs == null) return List.of();
        var out = new ArrayList<String>();
        for (var s : xs) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    // ─── Transcript helpers ───────────────────────────────────────

    private static Map<String, List<AgentTranscript>> groupByHypothesis(Map<String, AgentTranscript> agents) {
        var out = new LinkedHashMap<String, List<AgentTranscript>>();
        for (var a : agents.values()) {
            var hypo = a.tags.get("hypothesis");
            if (hypo == null || hypo.isBlank()) continue;
            out.computeIfAbsent(hypo, k -> new ArrayList<>()).add(a);
        }
        return out;
    }

    private static List<String> anchorList(Map<String, AgentTranscript> agents) {
        return agents.values().stream()
            .map(a -> a.tags.get("anchor"))
            .filter(s -> s != null && !s.isBlank())
            .distinct()
            .toList();
    }

    private static List<String> hypothesisList(Map<String, AgentTranscript> agents) {
        return agents.values().stream()
            .map(a -> a.tags.get("hypothesis"))
            .filter(s -> s != null && !s.isBlank())
            .distinct()
            .toList();
    }

    private static String scopeChips(Map<String, AgentTranscript> agents) {
        var bits = new ArrayList<String>();
        bits.add(agents.size() + " agents");
        var anchors = anchorList(agents);
        if (!anchors.isEmpty()) bits.add(anchors.size() + " anchors");
        var hypotheses = hypothesisList(agents);
        if (!hypotheses.isEmpty()) bits.add(hypotheses.size() + " hypotheses");
        return String.join(" · ", bits);
    }

    private static Optional<AgentTranscript> findOne(Map<String, AgentTranscript> agents, String kind) {
        return agents.values().stream().filter(a -> kind.equals(a.kind)).findFirst();
    }

    /**
     * Returns every agent whose kind matches any of {@code kinds}, in
     * the order they appear in the input list (which is the order the
     * apparatus produced them — i.e. chronological / iteration order).
     *
     * <p>Replaces an earlier {@code pickKind} helper that returned only
     * the first match. That selection was load-bearing in the wrong
     * direction: in runs where the supervisor / compiler / sceptic
     * triplet iterated (LOOP_TO_COMPILER → re-run → PASS_THROUGH), the
     * first supervisor entry carried loopback prose ("returned to
     * compiler") and the terminal verdict — the actual binding answer —
     * lived in the LAST entry. The narrator faithfully translated the
     * loopback prose and shipped the wrong outcome.
     *
     * <p>The narrator now sees every iteration and decides what to say.
     * No info gets thrown away on the way in.
     */
    private static List<AgentTranscript> pickAll(List<AgentTranscript> agents, String... kinds) {
        var allowed = java.util.Set.of(kinds);
        var out = new ArrayList<AgentTranscript>();
        for (var a : agents) if (allowed.contains(a.kind)) out.add(a);
        return out;
    }

    private static @Nullable AgentTranscript lastOrNull(List<AgentTranscript> xs) {
        return xs.isEmpty() ? null : xs.getLast();
    }

    /**
     * Render every iteration of a role as one block of text, with
     * explicit {@code [iter N / total]} markers and (when present) a
     * compact dump of the agent's structured output so the model sees
     * the supervisor's {@code decision} / {@code passReason}, the
     * forensic pathologist's {@code keyNumbers} /
     * {@code primaryClassification}, the advocate / prosecutor's
     * {@code argument} + {@code concessions}, etc. — typed fields it
     * can ground its narration in instead of inferring from prose.
     *
     * <p>{@code perIterCap} is the cap on the prose dump per iteration
     * — typed-output JSON is dumped in full (it's already small).
     */
    private String renderIterations(String label, List<AgentTranscript> iters, int perIterCap) {
        if (iters.isEmpty()) return "(no " + label + " entries)";
        var sb = new StringBuilder();
        for (int i = 0; i < iters.size(); i++) {
            var a = iters.get(i);
            sb.append("---- [iter ").append(i).append(" / ").append(iters.size())
              .append(" · kind=").append(a.kind).append("] ----\n");
            if (a.output != null && !a.output.isMissingNode() && !a.output.isNull()) {
                try {
                    sb.append("STRUCTURED OUTPUT (JSON, verbatim):\n");
                    sb.append(objectMapper.writeValueAsString(a.output));
                    sb.append("\n\n");
                } catch (Exception ignored) { /* fall through to prose */ }
            }
            sb.append("PROSE:\n");
            sb.append(truncate(a.rawResponse, perIterCap));
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private static String renderPeers(List<String> peers) {
        if (peers == null || peers.isEmpty()) return "(this is the only hypothesis in the run)";
        var sb = new StringBuilder();
        for (var p : peers) sb.append("- ").append(p).append("\n");
        return sb.toString();
    }

    @SafeVarargs
    private static @Nullable String elaborateOf(List<AgentTranscript>... groups) {
        var parts = new ArrayList<String>();
        for (var g : groups) {
            for (int i = 0; i < g.size(); i++) {
                var a = g.get(i);
                if (a.rawResponse.isEmpty()) continue;
                parts.add("[" + a.kind + " · iter " + i + "]\n" + a.rawResponse);
            }
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    private static @Nullable String joinTranscripts(@Nullable AgentTranscript... agents) {
        var parts = new ArrayList<String>();
        for (var a : agents) {
            if (a == null) continue;
            if (!a.rawResponse.isEmpty()) parts.add("[" + a.kind + "] " + a.rawResponse);
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n[…transcript truncated]";
    }

    // ─── Fallbacks (used when a section's LLM call fails) ─────────

    private RewindDTO.ReconSlide placeholderRecon(
            SessionAnalysis analysis,
            Map<String, AgentTranscript> agents,
            @Nullable AgentTranscript scout,
            @Nullable AgentTranscript domain) {
        var query = Optional.ofNullable(analysis.getQuery()).orElse("an open-ended causal question");
        var firstSentence = firstSentence(scout != null ? scout.rawResponse : "");
        return new RewindDTO.ReconSlide(
            "We asked: " + trimTrailingPunctuation(query) + ".",
            scopeChips(agents),
            firstSentence.isEmpty()
                ? "The apparatus opened with a reconnaissance pass to map the terrain — what entities are involved, what data exists, what corners would be sharp."
                : firstSentence,
            "An honest map of what holds up, not a clean story.",
            joinTranscripts(scout, domain));
    }

    private RewindDTO.HypothesisChain placeholderChain(
            String hypothesisName, String anchor,
            @Nullable AgentTranscript compiler,
            @Nullable AgentTranscript sceptic,
            @Nullable AgentTranscript forensic,
            @Nullable AgentTranscript advocate,
            @Nullable AgentTranscript prosecutor,
            @Nullable AgentTranscript supervisor) {
        var initialBody = firstSentence(compiler != null ? compiler.rawResponse : "");
        return new RewindDTO.HypothesisChain(
            anchor, hypothesisName,
            new RewindDTO.InitialProposal(
                "First idea on " + anchor,
                initialBody.isEmpty()
                    ? "The apparatus' opening guess for this anchor. Not yet stress-tested."
                    : initialBody,
                RewindDTO.Visual.arrow,
                compiler != null ? compiler.rawResponse : null),
            new RewindDTO.RefinedProposal(
                "After scrutiny",
                "A second pass tightened the claim.",
                "The wording got sharper after the sceptic and forensic passes.",
                RewindDTO.Visual.cluster,
                joinTranscripts(sceptic, forensic)),
            new RewindDTO.Verification(
                "Stress test",
                "The advocate and prosecutor traded rounds.",
                List.of("Edge cases were probed", "Assumptions were named"),
                List.of("Core claim held"),
                joinTranscripts(advocate, prosecutor)),
            new RewindDTO.Outcome(
                RewindDTO.VerdictTag.INCONCLUSIVE,
                "Standoff",
                "Both sides held meaningful ground.",
                null,
                supervisor != null ? supervisor.rawResponse : null,
                null));
    }

    private RewindDTO.VerdictSlides placeholderVerdict(
            @Nullable AgentTranscript judge,
            Map<String, AgentTranscript> agents) {
        var raw = judge != null ? judge.rawResponse : "";
        var headline = firstSentence(raw);
        return new RewindDTO.VerdictSlides(
            new RewindDTO.VerdictSlides.BottomLine(
                headline.isEmpty() ? "Here's what the apparatus is willing to defend." : headline,
                "Each hypothesis contributed its piece; together they make up the call.",
                null),
            new RewindDTO.VerdictSlides.Pattern(
                "Look at these conclusions — notice the pattern?",
                "Read together, the hypotheses converge on a common shape.",
                "The conclusions cluster around a single mechanism.",
                null),
            new RewindDTO.VerdictSlides.Caveats(
                "What we still can't say",
                "Some questions the data couldn't answer cleanly were flagged rather than guessed at."));
    }

    private static String firstSentence(String s) {
        if (s == null || s.isEmpty()) return "";
        var dot = s.indexOf('.');
        if (dot < 0) return s.trim();
        return s.substring(0, Math.min(dot + 1, s.length())).trim();
    }

    private static String trimTrailingPunctuation(String s) {
        if (s == null) return "";
        while (!s.isEmpty() && (s.endsWith(".") || s.endsWith("?") || s.endsWith("!"))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
