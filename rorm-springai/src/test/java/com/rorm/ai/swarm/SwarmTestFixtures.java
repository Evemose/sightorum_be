package com.rorm.ai.swarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.metamodel.ModelSpace;
import com.rorm.ml.stream.JobEventType;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Shared canned responses, WireMock stub setup, config, and result assertions
 * for {@link DurableSwarm} tests. Reusable across in-memory and Restate tests.
 */
public final class SwarmTestFixtures {

    public static final String SCOUT_RESPONSE = """
        ## Entity Census
        - shipments (500000, 30 attrs) [has_temporal, wide_table]
        - cold_nodes (28, 12 attrs) [small_table]
        - containers (200, 8 attrs) [small_table]
        - vehicles (150, 10 attrs) [small_table]
        ## Relationship Topology
        cold_nodes → shipments (1:N via nodeId)
        containers → shipments (1:N via containerId)
        vehicles → shipments (1:N via vehicleId)
        ## Schema Summary
        shipments(500K, TARGET:excursionFlag=12%) → cold_nodes(28) → containers(200) → vehicles(150)
        """;

    // ── Canned responses ─────────────────────────────────────────────────
    public static final String DOMAIN_RESPONSE = """
        ## Domain Identification
        - Domain: Pharmaceutical Cold Chain Logistics
        - Confidence: HIGH
        ## Reference Class Baselines
        - Excursion rate: 10-15% typical, <5% best-in-class
        ## Known Causal Drivers
        1. Ambient temperature (WELL_ESTABLISHED, 2-5x risk in summer)
        2. Equipment age (WELL_ESTABLISHED, degradation after 5-7 years)
        3. Container insulation (WELL_ESTABLISHED, VIP > PUR > XPS)
        """;
    public static final String GENERATOR_RESPONSE = """
        ## Analysis Summary
        Iterative stability selection surfaced container insulation type as anchor-internal treatment.
        
        -- H1 HYPOTHESIS START
        HYPOTHESIS:
          treatment: containerInsulationType
          outcome: excursionFlag
          expected_direction: -1
          DAG_EDGES:
            - (containerInsulationType, excursionFlag)
            - (ambientTempC, excursionFlag)
          EVIDENCE:
            stability_score: 0.82
            shap_curve_form: categorical
        -- H1 HYPOTHESIS END
        
        -- H2 HYPOTHESIS START
        HYPOTHESIS:
          treatment: containerAgeMonths
          outcome: excursionFlag
          expected_direction: +1
          DAG_EDGES:
            - (containerAgeMonths, excursionFlag)
          EVIDENCE:
            stability_score: 0.65
            shap_curve_form: threshold
            breakpoint: 36
        -- H2 HYPOTHESIS END
        """;
    public static final String SCEPTIC_RESPONSE = """
        VERIFICATION:
          claim: containerInsulationType has direction -1 on excursionFlag
          verdict: SUPPORTED
          delta: consistent across strata
        VERIFICATION:
          claim: containerAgeMonths breakpoint at 36 months
          verdict: CONDITIONAL
          delta: breakpoint shifts to 42 in hot zones
        COVERAGE SUMMARY: all plan items addressed
        """;
    public static final String REBUTTAL_RESPONSE = """
        ACCEPT on containerAgeMonths breakpoint — narrowed to 36-42 range.
        
        -- H1 HYPOTHESIS START
        HYPOTHESIS:
          treatment: containerInsulationType
          outcome: excursionFlag
          expected_direction: -1
          DAG_EDGES:
            - (containerInsulationType, excursionFlag)
            - (ambientTempC, excursionFlag)
          EVIDENCE:
            stability_score: 0.82
        -- H1 HYPOTHESIS END
        
        -- H2 HYPOTHESIS START
        HYPOTHESIS:
          treatment: containerAgeMonths
          outcome: excursionFlag
          expected_direction: +1
          DAG_EDGES:
            - (containerAgeMonths, excursionFlag)
          EVIDENCE:
            stability_score: 0.65
            breakpoint: 36-42 range
        -- H2 HYPOTHESIS END
        """;
    public static final String COMPILER_RESPONSE = """
        hypothesis_id: H1
        treatment: containerInsulationType
        outcome: excursionFlag
        treatment_form: CATEGORICAL
        query: {"from":"shipments","selector":{"@type":"root"}}
        expected_row_count: 500000
        dag_edges: containerInsulationType -> excursionFlag
        dsep_threshold: 0.03
        adjustment_set: [ambientTempC, nodeId]
        estimation_variants: [{id: primary, treatment_column: containerInsulationType, model_type: LinearDML}]
        gates: {nuisance_r2: {outcome_abort: 0.01}}
        sensitivity: {confounder_drops: []}
        residual_checks: {field_correlation: {threshold: 0.05}}
        range_checks: {vif: {threshold: 10}}
        """;
    public static final String FP_RESPONSE = """
        NULL DIAGNOSIS: H1
        CLASSIFICATION: DOMINATED_MECHANISM
        The container insulation effect is real but dominated by active refrigeration.
        ABSORPTION NARRATIVE:
        vehicleRefrigModel absorbed 72% of the marginal insulation signal.
        MECHANISM ASSESSMENT:
        The passive insulation mechanism is real but overwhelmed by active cooling.
        """;
    public static final String SPEC_EXTRACTION_RESPONSE = """
        {"hypothesisId":"H1","treatment":"containerInsulationType","outcome":"excursionFlag",\
        "treatmentForm":"CATEGORICAL","dataQuery":{"from":"shipments","selector":{"@type":"root"}},\
        "expectedRowCount":500000,"dagEdges":"containerInsulationType -> excursionFlag",\
        "dsepThreshold":0.03,"adjustmentSet":["ambientTempC","nodeId"],\
        "estimationVariants":[{"id":"primary","treatment_column":"containerInsulationType",\
        "treatment_form":"CATEGORICAL","model_type":"LinearDML","w_columns":["ambientTempC","nodeId"]}],\
        "gates":{"nuisance_r2":{"outcome_abort":0.01,"outcome_flag":0.05,"treatment_abort":0.005,\
        "treatment_flag":0.02,"treatment_structural_max_r2":0.75},"sanity":{"expected_direction":-1,\
        "abort_magnitude":0.5,"flag_magnitude":0.1},"placebo":{"flag_ratio":0.3}},\
        "sensitivity":{"confounder_drops":[],"confounder_adds":[]},\
        "residualChecks":{"field_correlation":{"threshold":0.05,"check_columns":[]}},\
        "rangeChecks":{"vif":{"threshold":10}}}""";
    public static final String GENERATOR_RESPONSE_VEHICLES = """
        ## Analysis Summary
        Fleet age analysis revealed vehicle refrigeration model age as primary treatment.
        
        -- H3 HYPOTHESIS START
        HYPOTHESIS:
          treatment: vehicleRefrigAge
          outcome: excursionFlag
          expected_direction: +1
          DAG_EDGES:
            - (vehicleRefrigAge, excursionFlag)
          EVIDENCE:
            stability_score: 0.75
            shap_curve_form: monotonic
        -- H3 HYPOTHESIS END
        """;
    public static final String REBUTTAL_RESPONSE_VEHICLES = """
        ACCEPT on vehicleRefrigAge — confirmed monotonic increase.
        
        -- H3 HYPOTHESIS START
        HYPOTHESIS:
          treatment: vehicleRefrigAge
          outcome: excursionFlag
          expected_direction: +1
          DAG_EDGES:
            - (vehicleRefrigAge, excursionFlag)
          EVIDENCE:
            stability_score: 0.75
        -- H3 HYPOTHESIS END
        """;
    public static final UUID PIPELINE_JOB_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    public static final String ANCHOR_CONTAINERS = "Anchor: containers\nPerspective: Equipment condition and aging";
    public static final String ANCHOR_VEHICLES = "Anchor: vehicles\nPerspective: Fleet age and maintenance cycles";
    /**
     * @deprecated use {@link #ANCHOR_CONTAINERS}
     */
    @Deprecated
    public static final String ANCHOR = ANCHOR_CONTAINERS;

    private SwarmTestFixtures() {
    }

    // ── Shared input ─────────────────────────────────────────────────────

    public static SwarmInput defaultInput() {
        return new SwarmInput(
            "How can I decrease excursion rates",
            "cold_chain",
            new ModelSpace(Set.of()),
            List.of(ANCHOR_CONTAINERS, ANCHOR_VEHICLES)
        );
    }

    // ── Shared config ────────────────────────────────────────────────────

    public static DurableSwarmConfig testConfig() {
        return new DurableSwarmConfig(
            new AgentModelConfig("claude-sonnet-4-6",
                "You are a data scout. Map the data landscape.",
                "<query>{{USER_QUERY}}</query>",
                ThinkingLevel.NONE, Set.of(), null),
            new AgentModelConfig("claude-sonnet-4-6",
                "You are a domain researcher. Provide external knowledge.",
                "<query>{{USER_QUERY}}</query>",
                ThinkingLevel.NONE, Set.of(), null),
            new AgentModelConfig("claude-opus-4-6",
                "You are a causal hypothesis generator.",
                "<query>{{USER_QUERY}}</query>\n<anchor>{{ANCHOR_ENTITY}}</anchor>\n<survey>{{CLUSTER_CONTEXT}}</survey>\n<domain>{{DOMAIN_RESEARCH}}</domain>",
                ThinkingLevel.NONE, Set.of(), null),
            "{{SCEPTIC_FINDINGS}}",
            new AgentModelConfig("claude-opus-4-6",
                "You verify causal hypothesis claims by computing alternative evidence.",
                "Generator output:\n{{GENERATOR_OUTPUT}}",
                ThinkingLevel.NONE, Set.of(), null),
            new AgentModelConfig("claude-opus-4-6",
                "You are a pipeline compiler.",
                "<hypothesis>{{HYPOTHESIS_SPEC}}</hypothesis>\n<domain>{{DOMAIN_KNOWLEDGE}}</domain>",
                ThinkingLevel.NONE, Set.of(), null),
            new AgentModelConfig("claude-opus-4-6",
                "You are a null hypothesis post-mortem analyst.",
                "<hypothesis>{{HYPOTHESIS_SPEC}}</hypothesis>\n<domain>{{DOMAIN_KNOWLEDGE}}</domain>\n<pipeline>{{PIPELINE_OUTPUT}}</pipeline>",
                ThinkingLevel.NONE, Set.of(), null),
            new AgentModelConfig("claude-haiku-4-5-20251001",
                "You are a summarizer. Extract a structured {{DTO_TYPE}} from: {{RAW_OUTPUT}}",
                "", ThinkingLevel.NONE, Set.of(), null)
        );
    }

    // ── WireMock stubs ───────────────────────────────────────────────────

    public static void registerAllStubs() {
        stubFor(post(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("data scout"))
            .willReturn(anthropicResponse(SCOUT_RESPONSE)));

        stubFor(post(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("domain researcher"))
            .willReturn(anthropicResponse(DOMAIN_RESPONSE)));

        // Anchor-specific generator/rebuttal for vehicles (higher priority)
        stubFor(post(urlEqualTo("/v1/messages"))
            .atPriority(1)
            .withRequestBody(containing("causal hypothesis generator"))
            .withRequestBody(containing("Anchor: vehicles"))
            .willReturn(anthropicResponse(GENERATOR_RESPONSE_VEHICLES)));

        // Default rebuttal (both anchors — chat memory content is not reliable for matching)
        stubFor(post(urlEqualTo("/v1/messages"))
            .atPriority(2)
            .withRequestBody(containing("VERIFICATION findings"))
            .willReturn(anthropicResponse(REBUTTAL_RESPONSE)));

        stubFor(post(urlEqualTo("/v1/messages"))
            .atPriority(5)
            .withRequestBody(containing("causal hypothesis generator"))
            .willReturn(anthropicResponse(GENERATOR_RESPONSE)));

        stubFor(post(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("verify causal hypothesis claims"))
            .willReturn(anthropicResponse(SCEPTIC_RESPONSE)));

        stubFor(post(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("pipeline compiler"))
            .willReturn(anthropicResponse(COMPILER_RESPONSE)));

        stubFor(post(urlEqualTo("/v1/messages"))
            .atPriority(1)
            .withRequestBody(containing("Extract a PipelineSpecRequest JSON"))
            .willReturn(anthropicResponse(SPEC_EXTRACTION_RESPONSE)));

        stubFor(post(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("null hypothesis post-mortem"))
            .willReturn(anthropicResponse(FP_RESPONSE)));

        stubFor(post(urlPathMatching("/analysis/causal-verification/async.*"))
            .willReturn(okJson("""
                {"status": "accepted", "analysis_id": "%s", "message": "Job submitted"}
                """.formatted(PIPELINE_JOB_ID))));
    }

    public static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder anthropicResponse(String text) {
        try {
            var mapper = new ObjectMapper();
            var response = mapper.createObjectNode()
                .put("id", "msg_" + UUID.randomUUID().toString().substring(0, 8))
                .put("type", "message")
                .put("role", "assistant")
                .put("model", "test-model")
                .put("stop_reason", "end_turn")
                .putNull("stop_sequence");
            var content = mapper.createArrayNode();
            content.add(mapper.createObjectNode().put("type", "text").put("text", text));
            response.set("content", content);
            var usage = mapper.createObjectNode()
                .put("input_tokens", 100)
                .put("output_tokens", 200)
                .put("cache_creation_input_tokens", 0)
                .put("cache_read_input_tokens", 0);
            response.set("usage", usage);
            return okJson(mapper.writeValueAsString(response));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Result assertions ────────────────────────────────────────────────

    public static void assertFullPipelineResult(SwarmResult result) {
        assertThat(result.scoutOutput()).isEqualTo(SCOUT_RESPONSE);
        assertThat(result.domainResearch()).isEqualTo(DOMAIN_RESPONSE);

        assertThat(result.anchorResults())
            .hasSize(2)
            .extracting(SwarmResult.AnchorResult::anchor)
            .containsExactly(ANCHOR_CONTAINERS, ANCHOR_VEHICLES);

        // ── Anchor 1: containers (2 hypotheses) ──
        var anchor1 = result.anchorResults().getFirst();
        assertThat(anchor1.generatorChatId()).startsWith("swarm-gen-");
        assertThat(anchor1.generatorOutput()).isEqualTo(GENERATOR_RESPONSE);
        assertThat(anchor1.scepticOutput()).isEqualTo(SCEPTIC_RESPONSE);
        assertThat(anchor1.revisedOutput()).isEqualTo(REBUTTAL_RESPONSE);

        assertThat(anchor1.hypothesisResults())
            .hasSize(2)
            .extracting(SwarmResult.HypothesisResult::hypothesisId,
                SwarmResult.HypothesisResult::compilerOutput)
            .containsExactly(tuple("H1", COMPILER_RESPONSE), tuple("H2", COMPILER_RESPONSE));

        assertThat(anchor1.hypothesisResults())
            .filteredOn(h -> h.hypothesisId().equals("H1"))
            .singleElement()
            .satisfies(h1 -> {
                assertThat(h1.hypothesisSpec())
                    .contains("treatment: containerInsulationType")
                    .doesNotContain("containerAgeMonths");
                assertThat(h1.pipelineResult().eventType()).isEqualTo(JobEventType.JOB_SUCCESS);
                assertThat(h1.pipelineResult().message()).isEqualTo("Pipeline completed successfully");
                assertThat(h1.diagnosis()).isEqualTo(FP_RESPONSE);
            });

        assertThat(anchor1.hypothesisResults())
            .filteredOn(h -> h.hypothesisId().equals("H2"))
            .singleElement()
            .satisfies(h2 -> {
                assertThat(h2.hypothesisSpec())
                    .contains("treatment: containerAgeMonths")
                    .doesNotContain("containerInsulationType");
                assertThat(h2.pipelineResult().eventType()).isEqualTo(JobEventType.JOB_SUCCESS);
                assertThat(h2.diagnosis()).isEqualTo(FP_RESPONSE);
            });

        // ── Anchor 2: vehicles (2 hypotheses — same rebuttal) ──
        var anchor2 = result.anchorResults().get(1);
        assertThat(anchor2.generatorOutput()).isEqualTo(GENERATOR_RESPONSE_VEHICLES);
        assertThat(anchor2.hypothesisResults())
            .hasSize(2)
            .extracting(SwarmResult.HypothesisResult::hypothesisId)
            .containsExactly("H1", "H2");
        assertThat(anchor2.hypothesisResults())
            .allSatisfy(h -> {
                assertThat(h.pipelineResult().eventType()).isEqualTo(JobEventType.JOB_SUCCESS);
                assertThat(h.diagnosis()).isEqualTo(FP_RESPONSE);
            });
    }

    public static void verifyWireMockDataFlow(WireMock client) {
        client.verifyThat(1, postRequestedFor(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("data scout")));
        client.verifyThat(1, postRequestedFor(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("domain researcher")));
        // 4 calls with generator system prompt (2 generator + 2 rebuttal share same prompt)
        client.verifyThat(4, postRequestedFor(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("causal hypothesis generator")));
        // 2 sceptic calls
        client.verifyThat(2, postRequestedFor(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("verify causal hypothesis claims")));
        // 4 spec extractions (2 per anchor)
        client.verifyThat(4, postRequestedFor(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("Extract a PipelineSpecRequest JSON")));
        // 4 pipeline submissions
        client.verifyThat(4, postRequestedFor(
            urlPathMatching("/analysis/causal-verification/async.*")));
        // 4 forensic pathologist calls
        client.verifyThat(4, postRequestedFor(urlEqualTo("/v1/messages"))
            .withRequestBody(containing("null hypothesis post-mortem")));
    }
}
