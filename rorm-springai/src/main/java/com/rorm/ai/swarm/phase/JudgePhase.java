package com.rorm.ai.swarm.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.swarm.*;
import com.rorm.ai.swarm.SwarmResult.AnchorResult;
import com.rorm.ai.swarm.SwarmResult.HypothesisResult;
import com.rorm.ai.swarm.dto.ForensicDiagnosisDTO;
import com.rorm.ai.swarm.dto.HypothesisGenerationDTO;
import com.rorm.ai.swarm.dto.JudgeVerdictDTO;
import com.rorm.ai.swarm.dto.StandoffArgumentDTO;
import com.rorm.ai.swarm.executor.StepExecutionInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Single, swarm-global phase that synthesizes advocate/prosecutor standoffs
 * from every hypothesis across every anchor into one user-facing answer.
 * There is no fanout: the phase assembles all surviving evidence into one
 * prompt and issues a single durable invocation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgePhase {

    private static final TypeReference<StepOutput<JudgeVerdictDTO>> REF = new TypeReference<>() {};

    private final DurableRuntime runtime;
    private final ObjectMapper mapper;
    private final DurableSwarmConfig config;

    public StepOutput<JudgeVerdictDTO> run(SwarmInput input, ReconPhase.Output recon,
                                           List<AnchorResult> anchors, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doRun(input, recon, anchors));
    }

    private StepOutput<JudgeVerdictDTO> doRun(SwarmInput input, ReconPhase.Output recon,
                                              List<AnchorResult> anchors) {
        var hypothesesBlock = renderHypotheses(anchors);
        var userPrompt = config.judge().userPromptTemplate()
            .replace("{{USER_QUERY}}", input.userQuery())
            .replace("{{DOMAIN_RESEARCH}}", recon.domain().rawResponse())
            .replace("{{CLUSTER_CONTEXT}}", recon.scout().rawResponse())
            .replace("{{HYPOTHESES}}", hypothesesBlock);

        var id = judgeId(input, anchors);
        var stepInput = StepExecutionInput.builder()
            .eventId(id).userPrompt(userPrompt).schema(input.schema()).runId(PhaseScope.runId())
            .build();

        log.info("[swarm] Judge synthesizing {} anchor(s) across {} hypothesis result(s)",
            anchors.size(), anchors.stream().mapToInt(a -> a.hypothesisResults().size()).sum());
        var sessionId = "judgeExecutor-" + id.token();
        return mapper.convertValue(
            runtime.submit(sessionId, new JobSpec(
                "judgeExecutor", "execute",
                new Object[]{stepInput},
                new String[]{StepExecutionInput.class.getName()}
            )), REF);
    }

    private String renderHypotheses(List<AnchorResult> anchors) {
        var sb = new StringBuilder();
        for (var anchor : anchors) {
            for (var hypo : anchor.hypothesisResults()) {
                sb.append(renderHypothesis(anchor, hypo));
            }
        }
        return sb.toString();
    }

    private EventId judgeId(SwarmInput input, List<AnchorResult> anchors) {
        var parents = new ArrayList<EventId>();
        var fingerprint = new java.util.TreeMap<String, String>();
        fingerprint.put("kind", "judge");
        fingerprint.put("schema", input.schema());
        fingerprint.put("query", input.userQuery());
        for (var anchor : anchors) {
            for (var hypo : anchor.hypothesisResults()) {
                fingerprint.put("adv:" + anchor.anchor() + "/" + hypo.hypothesisId(),
                    hypo.advocate() == null ? "" : hypo.advocate().argument());
                fingerprint.put("pro:" + anchor.anchor() + "/" + hypo.hypothesisId(),
                    hypo.prosecutor() == null ? "" : hypo.prosecutor().argument());
            }
        }
        return EventId.child("judge", ContentHash.of(fingerprint), parents,
            Map.of("scope", "swarm"));
    }

    private String renderHypothesis(AnchorResult anchor, HypothesisResult hypo) {
        var id = anchor.anchor().lines().findFirst().orElse("anchor").trim() + "/" + hypo.hypothesisId();
        var statement = findHypothesisDto(anchor.revisedOutput(), hypo.hypothesisId());
        return """
            <hypothesis id="%s">
              <statement>%s</statement>
              <compiler_output>%s</compiler_output>
              <forensic_diagnosis>%s</forensic_diagnosis>
              <advocate_argument>%s</advocate_argument>
              <prosecutor_argument>%s</prosecutor_argument>
            </hypothesis>
            """.formatted(
            escape(id),
            statement,
            hypo.compilerRaw(),
            renderDiagnosis(hypo.diagnosis()),
            renderArgument(hypo.advocate()),
            renderArgument(hypo.prosecutor())
        );
    }

    private String findHypothesisDto(HypothesisGenerationDTO revised, String title) {
        if (revised == null || revised.hypotheses() == null) {
            return title;
        }
        return revised.hypotheses().stream()
            .filter(h -> title.equals(h.title()))
            .findFirst()
            .map(this::writeJson)
            .orElse(title);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String renderDiagnosis(@Nullable ForensicDiagnosisDTO diagnosis) {
        if (diagnosis == null) {
            return "(no forensic diagnosis — hypothesis was not null)";
        }
        return writeJson(diagnosis);
    }

    private String renderArgument(@Nullable StandoffArgumentDTO arg) {
        if (arg == null) {
            return "(no argument produced — hypothesis routed to forensic diagnosis instead)";
        }
        return arg.argument();
    }
}
