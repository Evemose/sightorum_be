package com.rorm.client.chat.mock;

import com.rorm.ai.chat.StreamToken;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.SwarmEventBus;
import com.rorm.ai.swarm.SwarmStreamEvent;
import com.rorm.ai.swarm.dto.SupervisorVerdictDTO;
import com.rorm.ai.swarm.dto.SupervisorVerdictDTO.Decision;
import lombok.SneakyThrows;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Scripted producer of a branched {@link SwarmStreamEvent} timeline that
 * mirrors a realistic {@code DurableSwarm} run end-to-end. Three anchors fan
 * out in parallel after a shared recon phase; each anchor runs its own gen
 * trio (generator → mechanical sceptic → rebuttal) and then fans hypotheses
 * in parallel; eight hypotheses across the three anchors exercise every
 * supervisor outcome (clean PASS_THROUGH; LOOP_TO_SCEPTIC then PASS_THROUGH;
 * LOOP_TO_GENERATOR then HYPOTHESIS_DEAD with a forensic pathologist; null
 * results both with and without forensic). Every standoff and the judge
 * emit too. Pacing is calibrated so the full timeline runs ~90 seconds.
 */
class MockSwarmScript {

    private static final long CHUNK_MS = 40;
    private static final long THINK_CHUNK_MS = 50;
    private static final long TOOL_DELAY_MS = 700;
    private static final long PROGRESS_DELAY_MS = 180;
    private static final long PAUSE_AGENT_MS = 400;
    private static final long PAUSE_PHASE_MS = 800;
    private static final int TEXT_CHUNK_CHARS = 14;
    private static final int THINK_CHUNK_CHARS = 20;

    private final String runId;
    private final SwarmEventBus eventBus;
    private final ConcurrentMap<UUID, AtomicInteger> tokenSeqs = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AtomicInteger> roundSeqs = new ConcurrentHashMap<>();
    private final AtomicInteger peerSeq = new AtomicInteger();

    MockSwarmScript(String runId, SwarmEventBus eventBus) {
        this.runId = runId;
        this.eventBus = eventBus;
    }

    private static String supervisorProgress(SupervisorVerdictDTO output, String hyp, int iteration) {
        var label = switch (output.decision()) {
            case PASS_THROUGH -> "passing " + hyp + " through to the downstream phases";
            case LOOP_TO_COMPILER -> "routing " + hyp + " back to the compiler with a focused request";
            case LOOP_TO_GENERATOR -> "routing " + hyp + " back to the generator with a refinement request";
        };
        return "Iteration " + iteration + " — " + label + ".";
    }

    private static Map<String, String> hypTags(String hyp, String anchor, int iteration) {
        return Map.of("anchor", anchor, "hypothesis", hyp,
            "iteration", Integer.toString(iteration));
    }

    private static SupervisorVerdictDTO verdict(SupervisorVerdictDTO.Decision decision,
                                                SupervisorVerdictDTO.PassThroughReason reason,
                                                String explanation,
                                                String focusRequest,
                                                String refinementRequest,
                                                String notes) {
        return new SupervisorVerdictDTO(decision, reason, explanation,
            focusRequest, refinementRequest, notes);
    }

    private static EventId newRoot(String kind, Map<String, String> tags) {
        return EventId.root(kind, UUID.randomUUID(), tags);
    }

    private static EventId newChild(String kind, List<EventId> parents, Map<String, String> tags) {
        return EventId.child(kind, UUID.randomUUID(), parents, tags);
    }

    @SneakyThrows
    private static void sleep(long millis) {
        Thread.sleep(millis);
    }

    @SneakyThrows
    private static void join(Thread thread) {
        thread.join();
    }

    private static CleanContent h11Content() {
        return new CleanContent(
            MockSwarmContent.H1_1_COMPILER, MockSwarmContent.H1_1_SCEPTIC,
            MockSwarmContent.H1_1_SUPERVISOR_THINKING,
            MockSwarmContent.H1_1_SUPERVISOR_EXP, MockSwarmContent.H1_1_SUPERVISOR_NOTES,
            MockSwarmContent.H1_1_ADVOCATE, MockSwarmContent.H1_1_PROSECUTOR);
    }

    private static LoopScepticContent h12Content() {
        return new LoopScepticContent(
            MockSwarmContent.H1_2_COMPILER, MockSwarmContent.H1_2_SCEPTIC1,
            new PeerExchange(MockSwarmContent.PEER_QUESTION_AGE_TEMPORAL,
                MockSwarmContent.PEER_ANSWER_AGE_TEMPORAL),
            MockSwarmContent.H1_2_SUPERVISOR_THINKING_0, MockSwarmContent.H1_2_SUPERVISOR_EXP_0,
            MockSwarmContent.H1_2_FOCUS_REQUEST, MockSwarmContent.H1_2_SUPERVISOR_NOTES_0,
            MockSwarmContent.H1_2_SCEPTIC2,
            MockSwarmContent.H1_2_SUPERVISOR_THINKING_1, MockSwarmContent.H1_2_SUPERVISOR_EXP_1,
            MockSwarmContent.H1_2_SUPERVISOR_NOTES_1,
            MockSwarmContent.H1_2_ADVOCATE, MockSwarmContent.H1_2_PROSECUTOR);
    }

    private static DeadContent h13Content() {
        return new DeadContent(
            MockSwarmContent.H1_3_COMPILER, MockSwarmContent.H1_3_SCEPTIC,
            MockSwarmContent.H1_3_SUPERVISOR_THINKING,
            MockSwarmContent.H1_3_SUPERVISOR_EXP, MockSwarmContent.H1_3_SUPERVISOR_NOTES,
            MockSwarmContent.H1_3_PATHOLOGIST_THINKING, MockSwarmContent.H1_3_PATHOLOGIST_TEXT);
    }

    private static LoopGeneratorContent hv1Content() {
        return new LoopGeneratorContent(
            MockSwarmContent.HV1_COMPILER1, MockSwarmContent.HV1_SCEPTIC1,
            MockSwarmContent.HV1_SUPERVISOR_THINKING_0, MockSwarmContent.HV1_SUPERVISOR_EXP_0,
            MockSwarmContent.HV1_REFINEMENT_REQUEST, MockSwarmContent.HV1_SUPERVISOR_NOTES_0,
            MockSwarmContent.HV1_REFINEMENT_THINKING, MockSwarmContent.HV1_REFINEMENT_TEXT,
            MockSwarmContent.HV1_COMPILER2, MockSwarmContent.HV1_SCEPTIC2,
            MockSwarmContent.HV1_SUPERVISOR_THINKING_1, MockSwarmContent.HV1_SUPERVISOR_EXP_1,
            MockSwarmContent.HV1_SUPERVISOR_NOTES_1,
            MockSwarmContent.HV1_PATHOLOGIST_THINKING, MockSwarmContent.HV1_PATHOLOGIST_TEXT);
    }

    private static CleanContent hv2Content() {
        return new CleanContent(
            MockSwarmContent.HV2_COMPILER, MockSwarmContent.HV2_SCEPTIC,
            MockSwarmContent.HV2_SUPERVISOR_THINKING,
            MockSwarmContent.HV2_SUPERVISOR_EXP, MockSwarmContent.HV2_SUPERVISOR_NOTES,
            MockSwarmContent.HV2_ADVOCATE, MockSwarmContent.HV2_PROSECUTOR);
    }

    private static LoopScepticContent hr1Content() {
        return new LoopScepticContent(
            MockSwarmContent.HR1_COMPILER, MockSwarmContent.HR1_SCEPTIC1,
            new PeerExchange(MockSwarmContent.PEER_QUESTION_HUMIDITY_GRAIN,
                MockSwarmContent.PEER_ANSWER_HUMIDITY_GRAIN),
            MockSwarmContent.HR1_SUPERVISOR_THINKING_0, MockSwarmContent.HR1_SUPERVISOR_EXP_0,
            MockSwarmContent.HR1_FOCUS_REQUEST, MockSwarmContent.HR1_SUPERVISOR_NOTES_0,
            MockSwarmContent.HR1_SCEPTIC2,
            MockSwarmContent.HR1_SUPERVISOR_THINKING_1, MockSwarmContent.HR1_SUPERVISOR_EXP_1,
            MockSwarmContent.HR1_SUPERVISOR_NOTES_1,
            MockSwarmContent.HR1_ADVOCATE, MockSwarmContent.HR1_PROSECUTOR);
    }

    private static CleanContent hr2Content() {
        return new CleanContent(
            MockSwarmContent.HR2_COMPILER, MockSwarmContent.HR2_SCEPTIC,
            MockSwarmContent.HR2_SUPERVISOR_THINKING,
            MockSwarmContent.HR2_SUPERVISOR_EXP, MockSwarmContent.HR2_SUPERVISOR_NOTES,
            MockSwarmContent.HR2_ADVOCATE, MockSwarmContent.HR2_PROSECUTOR);
    }

    private static DeadContent hr3Content() {
        return new DeadContent(
            MockSwarmContent.HR3_COMPILER, MockSwarmContent.HR3_SCEPTIC,
            MockSwarmContent.HR3_SUPERVISOR_THINKING,
            MockSwarmContent.HR3_SUPERVISOR_EXP, MockSwarmContent.HR3_SUPERVISOR_NOTES,
            MockSwarmContent.HR3_PATHOLOGIST_THINKING, MockSwarmContent.HR3_PATHOLOGIST_TEXT);
    }

    private int nextTokenSeq(EventId id) {
        return tokenSeqs.computeIfAbsent(id.token(), _ -> new AtomicInteger()).getAndIncrement();
    }

    private int nextRoundSeq(EventId id) {
        return roundSeqs.computeIfAbsent(id.token(), _ -> new AtomicInteger()).getAndIncrement();
    }

    void run() {
        var recon = runRecon();
        var allStandoffs = Collections.synchronizedList(new ArrayList<EventId>());
        var anchorThreads = List.of(
            Thread.startVirtualThread(() -> allStandoffs.addAll(runContainersAnchor(recon))),
            Thread.startVirtualThread(() -> allStandoffs.addAll(runVehiclesAnchor(recon))),
            Thread.startVirtualThread(() -> allStandoffs.addAll(runRoutesAnchor(recon))));
        anchorThreads.forEach(MockSwarmScript::join);
        runJudge(new ArrayList<>(allStandoffs));
        eventBus.publish(runId, new SwarmStreamEvent.RunCompleted());
    }

    private List<EventId> runRecon() {
        var scout = newRoot("scout", Map.of());
        var domain = newRoot("domain-researcher", Map.of());
        emitStart(scout, "scout");
        emitStart(domain, "domain-researcher");
        var scoutThread = Thread.startVirtualThread(() -> streamScout(scout));
        streamDomain(domain);
        join(scoutThread);
        sleep(PAUSE_PHASE_MS);
        return List.of(scout, domain);
    }

    private void streamScout(EventId scout) {
        streamThinking(scout, MockSwarmContent.SCOUT_THINKING);
        emitToolWithProgress(scout, "getSchemaProfile",
            "Pulling the schema overview to see what entities and cardinalities we are working with.");
        streamText(scout, MockSwarmContent.SCOUT_TEXT_PART1);
        emitToolWithProgress(scout, "stratifiedGradient",
            "Stratifying the outcome rate by likely anchor candidates to find the largest divergences.");
        streamText(scout, MockSwarmContent.SCOUT_TEXT_PART2);
        emitProgress(scout, "Wrapping up the survey and proposing three anchor frames.");
        emitFinished(scout, "scout",
            MockSwarmContent.SCOUT_TEXT_PART1 + MockSwarmContent.SCOUT_TEXT_PART2, null);
    }

    private void streamDomain(EventId domain) {
        streamThinking(domain, MockSwarmContent.DOMAIN_THINKING);
        emitToken(domain, new StreamToken.ServerTool("web_search",
            "pharma cold chain excursion benchmark"));
        sleep(PROGRESS_DELAY_MS);
        emitProgress(domain, "Pulling reference baselines from the cold-chain literature.");
        sleep(TOOL_DELAY_MS - PROGRESS_DELAY_MS);
        streamText(domain, MockSwarmContent.DOMAIN_TEXT);
        emitProgress(domain, "Mapping each anchor candidate to its established causal driver.");
        emitFinished(domain, "domain-researcher", MockSwarmContent.DOMAIN_TEXT, null);
    }

    private List<EventId> runContainersAnchor(List<EventId> recon) {
        var rebuttal = runGenPhase(recon, "containers",
            MockSwarmContent.CONTAINERS_GEN_THINKING,
            MockSwarmContent.CONTAINERS_GEN_TEXT,
            MockSwarmContent.CONTAINERS_SCEPTIC_TEXT,
            MockSwarmContent.CONTAINERS_REBUTTAL_TEXT);
        return parallelHypotheses(rebuttal, "containers", List.of(
            parent -> runCleanPath(parent, "H1.1", "containers", h11Content()),
            parent -> runLoopScepticPath(parent, "H1.2", "containers", h12Content()),
            parent -> runDeadPath(parent, "H1.3", "containers", h13Content())));
    }

    private List<EventId> runVehiclesAnchor(List<EventId> recon) {
        var rebuttal = runGenPhase(recon, "vehicles",
            MockSwarmContent.VEHICLES_GEN_THINKING,
            MockSwarmContent.VEHICLES_GEN_TEXT,
            MockSwarmContent.VEHICLES_SCEPTIC_TEXT,
            MockSwarmContent.VEHICLES_REBUTTAL_TEXT);
        return parallelHypotheses(rebuttal, "vehicles", List.of(
            parent -> runLoopGeneratorPath(parent, "HV1", "vehicles", hv1Content()),
            parent -> runCleanPath(parent, "HV2", "vehicles", hv2Content())));
    }

    private List<EventId> runRoutesAnchor(List<EventId> recon) {
        var rebuttal = runGenPhase(recon, "routes",
            MockSwarmContent.ROUTES_GEN_THINKING,
            MockSwarmContent.ROUTES_GEN_TEXT,
            MockSwarmContent.ROUTES_SCEPTIC_TEXT,
            MockSwarmContent.ROUTES_REBUTTAL_TEXT);
        return parallelHypotheses(rebuttal, "routes", List.of(
            parent -> runLoopScepticPath(parent, "HR1", "routes", hr1Content()),
            parent -> runCleanPath(parent, "HR2", "routes", hr2Content()),
            parent -> runDeadPath(parent, "HR3", "routes", hr3Content())));
    }

    private List<EventId> parallelHypotheses(EventId rebuttal, String anchorTag,
                                             List<Function<EventId, EventId>> runners) {
        var standoffs = Collections.synchronizedList(new ArrayList<EventId>());
        var threads = runners.stream()
            .map(runner -> Thread.startVirtualThread(() -> standoffs.add(runner.apply(rebuttal))))
            .toList();
        threads.forEach(MockSwarmScript::join);
        return new ArrayList<>(standoffs);
    }

    private EventId runGenPhase(List<EventId> reconParents, String anchorTag,
                                String genThinking, String genText,
                                String scepticText, String rebuttalText) {
        var anchorTags = Map.of("anchor", anchorTag);
        var generator = newChild("generator", reconParents, anchorTags);
        emitStart(generator, "generator");
        streamThinking(generator, genThinking);
        emitToolWithProgress(generator, "crossTabulation",
            "Scanning treatment-outcome joint distributions for stable candidates on the " + anchorTag + " anchor.");
        emitToolWithProgress(generator, "rankedList",
            "Ranking the candidates by stability score before drafting the hypothesis blocks.");
        streamText(generator, genText);
        emitProgress(generator, "Drafting structured hypothesis blocks for the " + anchorTag + " anchor.");
        emitFinished(generator, "generator", genText, null);
        sleep(PAUSE_AGENT_MS);

        var sceptic = newChild("sceptic", List.of(generator), anchorTags);
        emitStart(sceptic, "sceptic");
        emitToolWithProgress(sceptic, "verifyConfounderCompleteness",
            "Verifying each hypothesis claim against alternative evidence at a different conditioning grain.");
        streamText(sceptic, scepticText);
        emitProgress(sceptic, "Compiling the verification verdicts into a structured review.");
        emitFinished(sceptic, "sceptic", scepticText, null);
        sleep(PAUSE_AGENT_MS);

        var rebuttal = newChild("rebuttal", List.of(sceptic), anchorTags);
        emitStart(rebuttal, "rebuttal");
        streamText(rebuttal, rebuttalText);
        emitProgress(rebuttal, "Issuing HOLD/NARROW/ACCEPT verdicts on each sceptic finding.");
        emitFinished(rebuttal, "rebuttal", rebuttalText, null);
        sleep(PAUSE_PHASE_MS);
        return rebuttal;
    }

    private EventId runCleanPath(EventId rebuttal, String hyp, String anchor, CleanContent c) {
        var compile = runCompiler(rebuttal, hypTags(hyp, anchor, 0), c.compilerText());
        var sceptic = runCompilerSceptic(compile, hypTags(hyp, anchor, 0), c.scepticText(), null);
        var supervisor = runSupervisor(sceptic, hyp, anchor, 0,
            verdict(SupervisorVerdictDTO.Decision.PASS_THROUGH,
                SupervisorVerdictDTO.PassThroughReason.WELL_FORMED,
                c.supervisorExplanation(), null, null, c.supervisorNotes()),
            c.supervisorThinking());
        return runStandoff(supervisor, hypTags(hyp, anchor, 0),
            c.advocateText(), c.prosecutorText());
    }

    private EventId runLoopScepticPath(EventId rebuttal, String hyp, String anchor,
                                       LoopScepticContent c) {
        var tags0 = hypTags(hyp, anchor, 0);
        var compile = runCompiler(rebuttal, tags0, c.compilerText());
        var sceptic0 = runCompilerSceptic(compile, tags0, c.firstScepticText(), c.peerExchange());
        var sup0 = runSupervisor(sceptic0, hyp, anchor, 0,
            verdict(Decision.LOOP_TO_COMPILER, null,
                c.supervisorExplanation0(), c.focusRequest(), null, c.supervisorNotes0()),
            c.supervisorThinking0());
        var tags1 = hypTags(hyp, anchor, 1);
        var sceptic1 = runScepticContinuation(sup0, tags1, c.secondScepticText());
        var sup1 = runSupervisor(sceptic1, hyp, anchor, 1,
            verdict(SupervisorVerdictDTO.Decision.PASS_THROUGH,
                SupervisorVerdictDTO.PassThroughReason.MARGINAL_RETURNS,
                c.supervisorExplanation1(), null, null, c.supervisorNotes1()),
            c.supervisorThinking1());
        return runStandoff(sup1, tags1, c.advocateText(), c.prosecutorText());
    }

    private EventId runLoopGeneratorPath(EventId rebuttal, String hyp, String anchor,
                                         LoopGeneratorContent c) {
        var tags0 = hypTags(hyp, anchor, 0);
        var compile0 = runCompiler(rebuttal, tags0, c.compilerText0());
        var sceptic0 = runCompilerSceptic(compile0, tags0, c.firstScepticText(), null);
        var sup0 = runSupervisor(sceptic0, hyp, anchor, 0,
            verdict(SupervisorVerdictDTO.Decision.LOOP_TO_GENERATOR, null,
                c.supervisorExplanation0(), null, c.refinementRequest(), c.supervisorNotes0()),
            c.supervisorThinking0());
        var tags1 = hypTags(hyp, anchor, 1);
        var refinement = runRefinement(sup0, tags1, c.refinementThinking(), c.refinementText());
        var compile1 = runCompiler(refinement, tags1, c.compilerText1());
        var sceptic1 = runCompilerSceptic(compile1, tags1, c.secondScepticText(), null);
        var sup1 = runSupervisor(sceptic1, hyp, anchor, 1,
            verdict(SupervisorVerdictDTO.Decision.PASS_THROUGH,
                SupervisorVerdictDTO.PassThroughReason.HYPOTHESIS_DEAD,
                c.supervisorExplanation1(), null, null, c.supervisorNotes1()),
            c.supervisorThinking1());
        return runForensicPathologist(sup1, tags1,
            c.pathologistThinking(), c.pathologistText());
    }

    private EventId runDeadPath(EventId rebuttal, String hyp, String anchor, DeadContent c) {
        var tags = hypTags(hyp, anchor, 0);
        var compile = runCompiler(rebuttal, tags, c.compilerText());
        var sceptic = runCompilerSceptic(compile, tags, c.scepticText(), null);
        var supervisor = runSupervisor(sceptic, hyp, anchor, 0,
            verdict(SupervisorVerdictDTO.Decision.PASS_THROUGH,
                SupervisorVerdictDTO.PassThroughReason.HYPOTHESIS_DEAD,
                c.supervisorExplanation(), null, null, c.supervisorNotes()),
            c.supervisorThinking());
        return runForensicPathologist(supervisor, tags,
            c.pathologistThinking(), c.pathologistText());
    }

    private EventId runCompiler(EventId parent, Map<String, String> tags, String body) {
        var id = newChild("compiler", List.of(parent), tags);
        emitStart(id, "compiler");
        streamThinking(id, MockSwarmContent.COMPILER_THINKING);
        emitToolWithProgress(id, "getEntityProfile",
            "Profiling the treatment column's prevalence and the outcome's base rate.");
        emitToolWithProgress(id, "analyzeExpression",
            "Deriving the structural-max R² from the actual data distributions.");
        streamText(id, body);
        emitProgress(id, "Translating the rebuttal hypothesis into a complete PipelineSpec.");
        emitFinished(id, "compiler", body, null);
        sleep(PAUSE_AGENT_MS);
        return id;
    }

    private EventId runCompilerSceptic(EventId parent, Map<String, String> tags,
                                       String body, PeerExchange peer) {
        var id = newChild("compiler-sceptic", List.of(parent), tags);
        emitStart(id, "compiler-sceptic");
        streamThinking(id, MockSwarmContent.COMPILER_SCEPTIC_THINKING);
        emitToolWithProgress(id, "verifyBundledVariable",
            "Scouting the adjustment_set for bundled-variable risk before committing to a re-execution.");
        if (peer != null) {
            emitPeerExchange("compiler-sceptic", "domain-researcher",
                peer.question(), peer.answer());
            emitProgress(id, "Pausing on the verification to confirm a grain question with the domain researcher.");
        }
        emitToolWithProgress(id, "reexecuteCausalPipeline",
            "Submitting a re-execution patch to measure the actual delta on the primary ATE.");
        streamText(id, body);
        emitProgress(id, "Compiling the verification verdicts and any re-execution deltas into a structured review.");
        emitFinished(id, "compiler-sceptic", body, null);
        sleep(PAUSE_AGENT_MS);
        return id;
    }

    private EventId runScepticContinuation(EventId parent, Map<String, String> tags, String body) {
        var id = newChild("compiler-sceptic", List.of(parent), tags);
        emitStart(id, "compiler-sceptic");
        streamThinking(id, MockSwarmContent.COMPILER_SCEPTIC_CONTINUATION_THINKING);
        emitToolWithProgress(id, "verifyBundledVariable",
            "Re-running the focused verification the supervisor asked for.");
        streamText(id, body);
        emitProgress(id, "Replacing the previous structured review with a fresh complete one.");
        emitFinished(id, "compiler-sceptic", body, null);
        sleep(PAUSE_AGENT_MS);
        return id;
    }

    private EventId runSupervisor(EventId parent, String hyp, String anchor, int iteration,
                                  SupervisorVerdictDTO output, String thinkingText) {
        var id = newChild("supervisor", List.of(parent), hypTags(hyp, anchor, iteration));
        emitStart(id, "supervisor");
        streamThinking(id, thinkingText);
        var summary = "Verdict: " + output.decision()
                      + (output.passReason() != null ? " (" + output.passReason() + ")" : "")
                      + ". " + output.explanation();
        streamText(id, summary);
        emitProgress(id, supervisorProgress(output, hyp, iteration));
        emitFinished(id, "supervisor", summary, output);
        sleep(PAUSE_AGENT_MS);
        return id;
    }

    private EventId runRefinement(EventId parent, Map<String, String> tags,
                                  String thinking, String text) {
        var id = newChild("supervisor-refinement", List.of(parent), tags);
        emitStart(id, "supervisor-refinement");
        streamThinking(id, thinking);
        streamText(id, text);
        emitProgress(id, "Producing a revised hypothesis spec that addresses the supervisor's refinement.");
        emitFinished(id, "supervisor-refinement", text, null);
        sleep(PAUSE_AGENT_MS);
        return id;
    }

    private EventId runForensicPathologist(EventId parent, Map<String, String> tags,
                                           String thinking, String text) {
        var id = newChild("forensic-pathologist", List.of(parent), tags);
        emitStart(id, "forensic-pathologist");
        streamThinking(id, thinking);
        streamText(id, text);
        emitProgress(id, "Diagnosing the null result as either a dominated mechanism, a structural constraint, or full mediation.");
        emitFinished(id, "forensic-pathologist", text, null);
        sleep(PAUSE_AGENT_MS);
        return id;
    }

    private EventId runStandoff(EventId parent, Map<String, String> tags,
                                String advocateText, String prosecutorText) {
        var advocate = newChild("advocate", List.of(parent), tags);
        var prosecutor = newChild("prosecutor", List.of(parent), tags);
        emitStart(advocate, "advocate");
        emitStart(prosecutor, "prosecutor");
        var advocateThread = Thread.startVirtualThread(() -> {
            streamThinking(advocate, MockSwarmContent.ADVOCATE_THINKING);
            streamText(advocate, advocateText);
            emitProgress(advocate, "Drafting the case for accepting the hypothesis as an intervention candidate.");
            emitFinished(advocate, "advocate", advocateText, null);
        });
        streamThinking(prosecutor, MockSwarmContent.PROSECUTOR_THINKING);
        streamText(prosecutor, prosecutorText);
        emitProgress(prosecutor, "Drafting the case against accepting the hypothesis without caveats.");
        emitFinished(prosecutor, "prosecutor", prosecutorText, null);
        join(advocateThread);
        sleep(PAUSE_PHASE_MS);
        return prosecutor;
    }

    private void runJudge(List<EventId> standoffs) {
        var id = newChild("judge", standoffs, Map.of());
        emitStart(id, "judge");
        streamThinking(id, MockSwarmContent.JUDGE_THINKING);
        streamText(id, MockSwarmContent.JUDGE_TEXT);
        emitProgress(id, "Synthesising the eight hypotheses into a phased action sequence.");
        emitFinished(id, "judge", MockSwarmContent.JUDGE_TEXT, null);
    }

    private void emitPeerExchange(String fromRole, String toRole, String question, String answer) {
        var qId = newChild("peer-question", List.of(),
            Map.of("askerRole", fromRole, "targetRole", toRole));
        var qSeq = peerSeq.getAndIncrement();
        eventBus.publish(runId, new SwarmStreamEvent.AgentQuestion(qId, qSeq, fromRole, toRole, question));
        sleep(TOOL_DELAY_MS);
        var aId = newChild("peer-answer", List.of(qId),
            Map.of("askerRole", fromRole, "targetRole", toRole));
        var aSeq = peerSeq.getAndIncrement();
        eventBus.publish(runId, new SwarmStreamEvent.AgentAnswer(aId, aSeq, toRole, fromRole, answer));
        sleep(PAUSE_AGENT_MS);
    }

    // ─────────────────── Per-hypothesis content bundles ───────────────────

    private void streamText(EventId id, String text) {
        streamChunked(id, text, TEXT_CHUNK_CHARS, CHUNK_MS, false);
    }

    private void streamThinking(EventId id, String text) {
        streamChunked(id, text, THINK_CHUNK_CHARS, THINK_CHUNK_MS, true);
    }

    @SneakyThrows
    private void streamChunked(EventId id, String text, int chunkSize, long tickMs, boolean thinking) {
        int len = text.length();
        for (int i = 0; i < len; i += chunkSize) {
            var chunk = text.substring(i, Math.min(i + chunkSize, len));
            emitToken(id, thinking ? new StreamToken.Thinking(chunk) : new StreamToken.Text(chunk));
            Thread.sleep(tickMs);
        }
    }

    private void emitStart(EventId id, String kind) {
        eventBus.publish(runId, new SwarmStreamEvent.AgentStarted(id, kind));
    }

    private void emitToken(EventId id, StreamToken token) {
        eventBus.publish(runId, new SwarmStreamEvent.AgentToken(id, nextTokenSeq(id), token));
    }

    private void emitFinished(EventId id, String kind, String raw, Object output) {
        eventBus.publish(runId, new SwarmStreamEvent.AgentFinished(id, kind, raw, output));
    }

    private void emitProgress(EventId id, String message) {
        eventBus.publish(runId, new SwarmStreamEvent.AgentProgress(id, nextRoundSeq(id), message));
    }

    @SneakyThrows
    private void emitToolWithProgress(EventId id, String toolName, String progressMessage) {
        emitToken(id, new StreamToken.ToolCall(toolName));
        Thread.sleep(PROGRESS_DELAY_MS);
        emitProgress(id, progressMessage);
        Thread.sleep(TOOL_DELAY_MS - PROGRESS_DELAY_MS);
    }

    private record CleanContent(
        String compilerText, String scepticText,
        String supervisorThinking, String supervisorExplanation, String supervisorNotes,
        String advocateText, String prosecutorText
    ) {}

    private record LoopScepticContent(
        String compilerText, String firstScepticText, PeerExchange peerExchange,
        String supervisorThinking0, String supervisorExplanation0,
        String focusRequest, String supervisorNotes0,
        String secondScepticText,
        String supervisorThinking1, String supervisorExplanation1, String supervisorNotes1,
        String advocateText, String prosecutorText
    ) {}

    private record LoopGeneratorContent(
        String compilerText0, String firstScepticText,
        String supervisorThinking0, String supervisorExplanation0,
        String refinementRequest, String supervisorNotes0,
        String refinementThinking, String refinementText,
        String compilerText1, String secondScepticText,
        String supervisorThinking1, String supervisorExplanation1, String supervisorNotes1,
        String pathologistThinking, String pathologistText
    ) {}

    private record DeadContent(
        String compilerText, String scepticText,
        String supervisorThinking, String supervisorExplanation, String supervisorNotes,
        String pathologistThinking, String pathologistText
    ) {}

    private record PeerExchange(String question, String answer) {}
}
