package com.rorm.client.chat.mock;

/**
 * Canned realistic content for the {@link MockSwarmScript} timeline. Strings
 * are tuned to feel like real DurableSwarm outputs in the cold-chain example
 * domain so a frontend can verify rendering, line wrapping, structured-block
 * detection, and supervisor-routing visualisations against representative
 * payloads. Nothing here is loaded from the production swarm — everything is
 * inlined to keep the mock self-contained.
 * <p>
 * Sections are organised by the three anchors the mock fans out: containers
 * (equipment-side treatments), vehicles (fleet-side), and routes
 * (operational/temporal). Each anchor exercises a different mix of supervisor
 * outcomes.
 */
final class MockSwarmContent {

    static final String SCOUT_THINKING = """
        Examining schema cold_chain — four entities visible: shipments (fact, 500K rows), \
        containers (200), vehicles (150), nodes (28). Outcome candidate excursionFlag is binary, \
        base rate 0.12. Scanning for stratification handles by cardinality and outcome divergence \
        across container, vehicle, and route axes — three plausible anchor frames.""";

    // ─────────────────── Shared: recon ───────────────────
    static final String SCOUT_TEXT_PART1 = """
        ## Headline Survey
        Cold-chain shipments dataset spans 500,000 shipments across 28 nodes, 200 containers, \
        and 150 vehicles. Fleet-wide excursion rate sits at 0.12 with material divergence \
        across container insulation classes (PUR=0.18, XPS=0.11, VIP=0.05), vehicle refrigeration \
        generations (legacy_2008=0.21, mid_2014=0.13, modern_2020=0.07), and seasonal humidity \
        bands (summer >60% RH = 0.17, otherwise 0.10).
        
        """;
    static final String SCOUT_TEXT_PART2 = """
        ## Anchor candidates
        ANCHOR 1 — containers: equipment-side treatments. Strong base-rate spread on \
        insulation; secondary signals on age and material thickness.
        ANCHOR 2 — vehicles: fleet-side treatments. Refrigeration generation correlates with \
        outcome; vehicleEquipmentCohort surfaces but is composed.
        ANCHOR 3 — routes: operational/temporal treatments. Humidity gradient, dwell time, \
        route distance — seasonal stratification likely.
        
        ## Hazards
        HAZARD: fanout_risk on cold_nodes — 17857 shipments per node, aggregation required \
        before joining.
          severity: MEDIUM
          blocks_analysis: false
        
        ## Schema Summary
        shipments(500K, TARGET:excursionFlag=0.12) → cold_nodes(28) → containers(200) → \
        vehicles(150)
        """;
    static final String DOMAIN_THINKING = """
        Identifying domain. Cold-chain logistics with binary excursion outcome — pharmaceutical \
        distribution shape. Pulling reference baselines and known causal drivers from the \
        literature; mapping each candidate anchor to its established mechanisms.""";
    static final String DOMAIN_TEXT = """
        ## Domain Identification
        Domain: Pharmaceutical Cold Chain Logistics. Confidence: HIGH.
        
        ## Reference Class Baselines
        Industry excursion rate: 10–15% typical, <5% best-in-class operators. The fleet's \
        12% sits at the median.
        
        ## Known Causal Drivers (peer-reviewed)
        1. Container insulation material — WELL_ESTABLISHED. VIP reduces excursion 3–5× vs \
        PUR via lower thermal conductivity (k=0.005 vs 0.025 W/m·K).
        2. Refrigeration unit age — WELL_ESTABLISHED. Active cooling efficiency degrades \
        ~15% per decade after the 5-year mark.
        3. Ambient temperature × humidity exposure — WELL_ESTABLISHED. 2–5× excursion risk \
        in summer; humidity amplifies temperature stress.
        4. Loading/unloading dwell time — MODERATELY_ESTABLISHED. Each minute of door-open \
        exposure raises internal temperature 0.3–0.8°C.
        5. Route distance — WEAK. Independent of distance once dwell time is controlled.
        
        ## Treatment Recommendation
        Highest-leverage interventions: (a) VIP retrofits on PUR baseline, (b) refrigeration \
        unit replacement at 5-year mark, (c) summer route planning to avoid high-humidity \
        nodes. Distance and material thickness are secondary.
        """;
    static final String COMPILER_THINKING = """
        Translating the rebuttal hypothesis into a PipelineSpec. Profiling treatment column \
        prevalence and outcome base rate; deriving structural-max R² for the gates from the \
        actual data; documenting any generator/compiler discrepancies.""";

    // ─────────────────── Shared: per-step preambles ───────────────────
    static final String COMPILER_SCEPTIC_THINKING = """
        Stage 1: scout suspect compiler decisions via verification tools, parallel where \
        independent. Stage 2: form mutation hypotheses from material findings. Stage 3: \
        re-execute with patches and measure the actual delta.""";
    static final String COMPILER_SCEPTIC_CONTINUATION_THINKING = """
        Continuation: re-running with the supervisor's focus. Either the compile or the \
        spec defect is resolved; verifying the substantive estimation directly.""";
    static final String ADVOCATE_THINKING = """
        Building the strongest defensible case for the hypothesis given the trio's evidence. \
        Anchoring on the largest verified effect, citing the forensic narrative if relevant.""";
    static final String PROSECUTOR_THINKING = """
        Building the strongest defensible case against the hypothesis or for tempering its \
        operational implications. Watching for sample-size caveats and competing interventions.""";
    static final String JUDGE_THINKING = """
        Synthesising eight hypotheses across three anchors into a single recommendation. \
        Weighting by effect size, confidence interval coverage, and operational feasibility. \
        Sequencing the action plan by anchor — equipment retrofits first (largest effect), \
        operational adjustments second (lower cost), fleet upgrades third (deferred to \
        post-stabilisation).""";
    static final String JUDGE_TEXT = """
        ## Verdict
        
        Across three anchors and eight hypotheses, the dominant causal driver of cold-chain \
        excursions is **container insulation** (containers/H1.1, ATE -7.1pp, CI [-8.2, -5.9]). \
        The primary intervention recommendation is a targeted vacuum-insulated-panel (VIP) \
        retrofit on the polyurethane (PUR) baseline cohort.
        
        Material secondary findings:
        - **routes/H3.1 (nodeAmbientHumidity, summer-scoped)**: real seasonal effect of \
        +2.4pp at humidity ≥ 60% RH during June–August, robust under the supervisor-driven \
        re-check of W composition. Operationally narrow (~28% of annual shipments) but \
        actionable via route planning.
        - **routes/H3.2 (dwellTimeMinutes)**: monotonic +0.6pp per 10-minute dwell increase, \
        clean estimation. Tractable by operational tightening of loading windows.
        - **vehicles/H2.2 (vehicleRefrigGeneration)**: -3.1pp moving from legacy_2008 to \
        modern_2020 in stratified analysis — material once H1.1 normalises insulation.
        
        Null / dominated findings:
        - **containers/H1.3 (containerMaterialThickness)**: empirical null at fleet scale. \
        Mechanism subsumed by insulation type.
        - **vehicles/H2.1 (vehicleEquipmentCohort)**: composed treatment was decomposed; \
        residual vehicleRefrigModel signal is dominated by H1.1 at fleet scale (forensic: \
        DOMINATED_MECHANISM).
        - **routes/H3.3 (routeDistanceKm)**: empirical null. Distance is a confound for \
        dwell time and exposure — independent contribution disappears once those are \
        controlled.
        
        ## Recommended Action Sequence
        1. **Phase 1 (Q1–Q2)** — VIP retrofit on PUR containers. Projected: ~ -5pp \
        fleet-wide. Anchor commitment.
        2. **Phase 2 (Q3)** — Summer route adjustment for nodes >60% RH ambient + dwell \
        time tightening below 12 minutes per stop. Projected: -1.0pp combined annualised.
        3. **Phase 3 (post-stabilisation)** — Re-evaluate vehicleRefrigGeneration \
        intervention after H1.1 rollout normalises the insulation baseline.
        
        ## Confidence
        Primary (containers/H1.1): **HIGH**. Secondary (routes/H3.1, H3.2): **MEDIUM**. \
        Tertiary (vehicles/H2.2 deferred): **MEDIUM-HIGH post-stabilisation**.
        """;
    static final String CONTAINERS_GEN_THINKING = """
        Anchor: containers. Stability-selecting equipment-side treatments. Insulation type \
        is the dominant signal (PUR/XPS/VIP base-rate spread). Container age and material \
        thickness are weaker secondary candidates.""";

    // ─────────────────── Anchor: containers ───────────────────
    static final String CONTAINERS_GEN_TEXT = """
        ## Analysis Summary
        Anchor: containers. Three hypotheses surface with stability_score ≥ 0.55.
        
        -- H1.1 HYPOTHESIS START
        HYPOTHESIS:
          treatment: containerInsulationType
          outcome: excursionFlag
          expected_direction: -1
          DAG_EDGES:
            - (containerInsulationType, excursionFlag)
            - (ambientTempC, excursionFlag)
            - (containerAge, excursionFlag)
          EVIDENCE:
            stability_score: 0.84
            shap_curve_form: categorical
            base_rates_per_level: {PUR: 0.18, XPS: 0.11, VIP: 0.05}
        -- H1.1 HYPOTHESIS END
        
        -- H1.2 HYPOTHESIS START
        HYPOTHESIS:
          treatment: containerAge
          outcome: excursionFlag
          expected_direction: +1
          DAG_EDGES:
            - (containerAge, excursionFlag)
            - (containerInsulationType, excursionFlag)
          EVIDENCE:
            stability_score: 0.62
            shap_curve_form: monotonic
            breakpoint: 4.5 years
        -- H1.2 HYPOTHESIS END
        
        -- H1.3 HYPOTHESIS START
        HYPOTHESIS:
          treatment: containerMaterialThickness
          outcome: excursionFlag
          expected_direction: -1
          DAG_EDGES:
            - (containerMaterialThickness, excursionFlag)
            - (containerInsulationType, containerMaterialThickness)
          EVIDENCE:
            stability_score: 0.55
            shap_curve_form: weak_monotonic
        -- H1.3 HYPOTHESIS END
        """;
    static final String CONTAINERS_SCEPTIC_TEXT = """
        VERIFICATION:
          claim: containerInsulationType reduces excursion (H1.1)
          alternative_path: stratifiedGradient by insulationType, conditioning on nodeRegion
          claimedNumber: -7pp; verifiedNumber: -6.8pp; delta: 0.2pp
          verdict: SUPPORTED
        
        VERIFICATION:
          claim: containerAge breakpoint at 4.5 years (H1.2)
          alternative_path: thresholdLocation across age bins
          claimedNumber: 4.5y; verifiedNumber: 5.1y
          verdict: CONDITIONAL — breakpoint shifts to 5.1y once nodeRegion is stratified.
        
        VERIFICATION:
          claim: containerMaterialThickness reduces excursion (H1.3)
          alternative_path: corr(thickness, outcome | insulationType)
          finding: residual correlation is +0.012 once insulationType is conditioned on.
          verdict: OVERSTATED — direction holds, magnitude drops to noise floor.
        
        COVERAGE_SUMMARY: 12 plan items; 1 conditional, 1 overstated.
        """;
    static final String CONTAINERS_REBUTTAL_TEXT = """
        HOLD on H1.1 — sceptic's stratified gradient confirmed direction within rounding.
        
        NARROW on H1.2 — accepting the 5.1y breakpoint shift; revising:
        -- H1.2 HYPOTHESIS START (revised)
          treatment: containerAge
          breakpoint: 5.1y primary, 4.5y validation
          adjustment_set hint: include nodeRegion
        -- H1.2 HYPOTHESIS END
        
        HOLD on H1.3 — keeping the hypothesis to let the executor's pipeline confirm whether \
        the residual signal is recoverable; sceptic's overstated finding is consistent with \
        a dominated mechanism, which the forensic pathologist (if invoked) can characterise.
        """;
    static final String H1_1_COMPILER = """
        Compiling H1.1: containerInsulationType → excursionFlag.
        
        PipelineSpec:
          treatment: containerInsulationType (CATEGORICAL, 3 levels) — reference: PUR
          outcome: excursionFlag (binary, p=0.12)
          adjustment_set: [ambientTempC, nodeRegion, containerAge]
          dsep_threshold: 0.045 (1.7× max independent-pair correlation 0.026)
          estimation_variants: [
            {id: primary, treatment_form: CATEGORICAL, model_type: LinearDML},
            {id: nonparam, model_type: NonParamDML}
          ]
          gates: { nuisance_r2: { treatment_abort: 0.014, treatment_structural_max_r2: 0.42 },
                   sanity: { expected_direction: -1, abort_magnitude: 0.05 } }
        """;

    // H1.1 — containerInsulationType — CLEAN_PASS
    static final String H1_1_SCEPTIC = """
        VERIFICATION:
          plan_item: gates.nuisance_r2.treatment_abort — pattern 10
          alternative_path: 4×p×(1−p) at p=0.33
          compiler_value: 0.014 (5% of 0.5); recomputed: 0.045 (5% of 0.89)
          markers: [GATE_DRIFT]
          verdict: CONTRADICTED; material: false (gate non-blocking; treatment R² is 0.21)
        
        VERIFICATION:
          plan_item: adjustment_set[0] (ambientTempC) — pattern 3
          per-level stddev 8.1 vs population 8.4 → not bundled. SUPPORTED.
        
        Pipeline output: ATE -7.1pp, CI [-8.2, -5.9], gates green, refutations passed.
        COVERAGE_SUMMARY: 14 patterns; 1 GATE_DRIFT (non-material).
        """;
    static final String H1_1_SUPERVISOR_EXP = """
        Pipeline ATE -7.1pp with CI excluding zero by 6pp; gates green; only finding is a \
        non-material GATE_DRIFT on a non-blocking gate. WELL_FORMED.""";
    static final String H1_1_SUPERVISOR_NOTES = """
        H1.1 iter 0: clean run. ATE -7.1pp [-8.2, -5.9], gates green except non-material \
        GATE_DRIFT. Decision: PASS_THROUGH / WELL_FORMED.""";
    static final String H1_1_SUPERVISOR_THINKING = """
        H1.1 iter 0: pipeline strong, sceptic clean. PASS_THROUGH / WELL_FORMED.""";
    static final String H1_1_ADVOCATE = """
        H1.1 holds the strongest evidence in the run. Effect is large and well-bounded \
        (-7.1pp, CI [-8.2, -5.9]); excludes the null by 6pp. Mechanism — VIP reducing \
        thermal conductivity 5× relative to PUR — is well-established and corroborated by \
        the per-level base rates. The single sceptic finding (GATE_DRIFT) is non-blocking \
        and does not bias the estimate. Recommend Phase-1 VIP retrofit on the PUR baseline \
        cohort as the highest-leverage intervention.
        """;
    static final String H1_1_PROSECUTOR = """
        Caveat the rollout. The estimate is dominated by the VIP subsample (n=412 of 500K, \
        0.08%). A stable conditional effect in that subsample does not guarantee fleet-wide \
        retrofit performance — VIP panels are tested on premium routes that may differ in \
        dwell time and ambient exposure. The literature's 5× advantage is a lab figure under \
        controlled cycling. Recommend a staged retrofit (10% pilot → 90% rollout) with \
        explicit non-premium-route monitoring.
        """;
    static final String H1_2_COMPILER = """
        Compiling H1.2 (revised): containerAge → excursionFlag with 5.1y breakpoint.
        
        PipelineSpec:
          treatment: containerAge (CONTINUOUS, breakpoint 5.1y for binary variant)
          outcome: excursionFlag
          adjustment_set: [containerInsulationType, nodeRegion, vehicleAge]
          estimation_variants: [
            {id: primary, treatment_form: CONTINUOUS, model_type: LinearDML},
            {id: thresh51, treatment_form: BINARY_THRESHOLD, threshold: 5.1}
          ]
        """;

    // H1.2 — containerAge — LOOP_TO_SCEPTIC then PASS / MARGINAL_RETURNS
    static final String H1_2_SCEPTIC1 = """
        VERIFICATION:
          plan_item: adjustment_set — pattern 5 (confounder completeness)
          verdict: SUPPORTED
        VERIFICATION:
          plan_item: estimation_variants[1] thresh51 — pattern 11 (positivity)
          coverage 71% of cells. SUPPORTED.
        
        Pipeline output: ATE +0.012 per year, thresh51 ATE +1.8pp, gates green.
        COVERAGE_SUMMARY: 7 patterns; 0 material findings.
        """;
    static final String H1_2_FOCUS_REQUEST = """
        Run pattern 9 (temporal confounding) on containerAge. Container age is mechanically \
        correlated with the time the container entered service; if there is a fleet-wide \
        drift in shipment volume or routing characteristics over the same span, that drift \
        could be confounding the age effect. Compute a temporal-trend check across years of \
        commissioning, partial out the year-fixed-effects, and report the residualised ATE \
        with a re-execution delta. Replace your previous review with a fresh COMPLETE one.
        """;
    static final String H1_2_SUPERVISOR_EXP_0 = """
        Sceptic's coverage skipped pattern 9 — containerAge has obvious temporal confounding \
        risk that warrants explicit checking before passing through.""";
    static final String H1_2_SUPERVISOR_NOTES_0 = """
        H1.2 iter 0: pipeline +1.8pp at age≥5.1y, sceptic clean but skipped pattern 9 \
        (temporal confounding) on a container-age treatment that is mechanically tied to \
        commissioning year. Routing LOOP_TO_SCEPTIC with focusRequest naming year-fixed-effect \
        residualisation + re-execution delta.""";
    static final String H1_2_SUPERVISOR_THINKING_0 = """
        H1.2 iter 0: pipeline output looks plausible but sceptic skipped temporal-confounding \
        pattern 9. Container age has an obvious mechanical link to commissioning year. \
        Route to sceptic with focused request.""";
    static final String H1_2_SCEPTIC2 = """
        VERIFICATION (re-run with supervisor focus on pattern 9):
          plan_item: temporal_confounding on containerAge
          alternative_path: trendSeries by commissioning year, residualise, re-execute
          year-fixed-effect ATE: +1.6pp at age≥5.1y (vs +1.8pp unadjusted)
          delta: 0.2pp (10% attenuation)
          verdict: CONDITIONAL — direction holds, magnitude attenuates 10%.
          material: false (CI still excludes zero post-residualisation: [+0.4, +2.8]).
        
        COVERAGE_SUMMARY: pattern 9 added. ATE attenuated 10% but conclusion unchanged.
        """;
    static final String H1_2_SUPERVISOR_EXP_1 = """
        Sceptic ran pattern 9 and confirmed temporal confounding attenuates the effect by \
        10% but does not flip direction or cross zero. ATE remains +1.6pp at age≥5.1y. \
        MARGINAL_RETURNS — additional iteration would not move the estimate.""";
    static final String H1_2_SUPERVISOR_NOTES_1 = """
        H1.2 iter 1: temporal confounding accounted for; ATE attenuated 10% but the effect \
        survives at +1.6pp [+0.4, +2.8]. PASS_THROUGH / MARGINAL_RETURNS.""";
    static final String H1_2_SUPERVISOR_THINKING_1 = """
        H1.2 iter 1: pattern 9 result attenuates ATE 10% — conclusion intact. No further \
        loop yields material change.""";
    static final String H1_2_ADVOCATE = """
        H1.2 surfaces a real but modest container-age effect. Once temporal confounding is \
        residualised (per the supervisor's focused re-check), the ATE is +1.6pp at age \
        ≥ 5.1 years with CI excluding zero. This translates to a fleet-renewal policy: \
        retire containers before the 5-year mark to avoid the post-threshold excursion \
        premium. Combined with the H1.1 insulation finding, the equipment-side intervention \
        envelope is clear: replace old PUR containers with new VIP units.
        """;
    static final String H1_2_PROSECUTOR = """
        +1.6pp is an order of magnitude smaller than H1.1's -7.1pp insulation effect. The \
        breakpoint is also fragile (4.5y → 5.1y under stratification), suggesting the age \
        effect is partially a proxy for insulation-class composition: older containers are \
        disproportionately PUR. Recommend treating containerAge as a renewal-prioritisation \
        signal rather than a standalone intervention; rolled into the H1.1 retrofit \
        decision, the marginal value of an age-only policy is small.
        """;
    static final String H1_3_COMPILER = """
        Compiling H1.3: containerMaterialThickness → excursionFlag.
        
        PipelineSpec:
          treatment: containerMaterialThickness (CONTINUOUS, range 12–48mm)
          outcome: excursionFlag
          adjustment_set: [containerInsulationType, ambientTempC, nodeRegion]
          NOTE: containerInsulationType is in W per generator NOTE on partial dependence.
        """;

    // H1.3 — containerMaterialThickness — DEAD_NO_LOOP
    static final String H1_3_SCEPTIC = """
        VERIFICATION:
          plan_item: adjustment_set inclusion of containerInsulationType — pattern 2 (mediator)
          finding: thickness is mechanically a feature of insulation type (VIP=12mm, XPS=24mm, \
        PUR=48mm). With insulation type in W, thickness has near-zero residual variance.
          verdict: BUNDLED via class — independent thickness effect not estimable in this \
        specification.
        
        Pipeline output: ATE -0.1pp per mm, CI [-0.4, +0.2] crosses zero.
        COVERAGE_SUMMARY: 8 patterns; 1 BUNDLED finding (structural).
        """;
    static final String H1_3_SUPERVISOR_EXP = """
        ATE -0.1pp/mm with CI [-0.4, +0.2] crossing zero; sceptic identified the structural \
        bundling against insulation type. The hypothesis is empirically null at fleet scale \
        — thickness adds no information beyond insulation class. HYPOTHESIS_DEAD.""";
    static final String H1_3_SUPERVISOR_NOTES = """
        H1.3: thickness bundled with insulation class (VIP=12mm, XPS=24mm, PUR=48mm); CI \
        crosses zero. Empirical null with structural cause; no recoverable iteration.""";
    static final String H1_3_SUPERVISOR_THINKING = """
        H1.3 iter 0: thickness is bundled with insulation type; ATE confidence interval \
        crosses zero. No path back; mark DEAD; forensic pathologist takes over to \
        characterise the bundling structurally.""";
    static final String H1_3_PATHOLOGIST_THINKING = """
        H1.3 returned a null direct effect with a structural bundling against insulation \
        class flagged by the sceptic. Diagnosing whether the bundling is design-induced \
        (each insulation class ships at a fixed thickness) or operationally avoidable \
        (mixed-thickness retrofits possible).""";
    static final String H1_3_PATHOLOGIST_TEXT = """
        NULL DIAGNOSIS: H1.3 (containerMaterialThickness)
        
        CLASSIFICATION: BUNDLED_DESIGN_CONSTRAINT
        
        ABSORPTION NARRATIVE:
        Thickness is determined by insulation class within standard product lines: VIP=12mm \
        (k=0.005), XPS=24mm (k=0.025), PUR=48mm (k=0.025). Once insulation type is in W, \
        within-class thickness variance is below sensor resolution (±0.4mm). The +0.005 \
        residual correlation observed by the sceptic is at the noise floor.
        
        MECHANISM ASSESSMENT:
        Thickness alone is not a free design variable in this fleet — choosing thickness is \
        choosing insulation class. The H1.1 retrofit decision already controls thickness \
        through the class change (VIP retrofit reduces thickness 4× while reducing thermal \
        conductivity 5×). There is no recoverable thickness effect distinct from H1.1.
        
        OPERATIONAL IMPLICATION:
        Drop containerMaterialThickness from the intervention slate. Re-test only if a \
        future product line introduces mixed-thickness variants within an insulation class.
        
        CONFIDENCE: HIGH on the design-constraint classification.
        """;
    static final String VEHICLES_GEN_THINKING = """
        Anchor: vehicles. Stability-selecting fleet-side treatments. Two candidates surface — \
        an active-cooling generation field (vehicleRefrigGeneration) and a derived cohort \
        column (vehicleEquipmentCohort). Flagging the cohort as composed.""";

    // ─────────────────── Anchor: vehicles ───────────────────
    static final String VEHICLES_GEN_TEXT = """
        ## Analysis Summary
        Anchor: vehicles. Two hypotheses with stability_score ≥ 0.66.
        
        -- HV1 HYPOTHESIS START
        HYPOTHESIS:
          treatment: vehicleEquipmentCohort
          outcome: excursionFlag
          expected_direction: -1
          DAG_EDGES:
            - (vehicleEquipmentCohort, excursionFlag)
            - (vehicleAge, vehicleEquipmentCohort)
          EVIDENCE:
            stability_score: 0.66
            shap_curve_form: ordinal
          NOTE: vehicleEquipmentCohort is composed of (vehicleMakeModel, vehicleRefrigModel) \
        per metamodel introspection.
        -- HV1 HYPOTHESIS END
        
        -- HV2 HYPOTHESIS START
        HYPOTHESIS:
          treatment: vehicleRefrigGeneration
          outcome: excursionFlag
          expected_direction: -1
          DAG_EDGES:
            - (vehicleRefrigGeneration, excursionFlag)
            - (vehicleAge, vehicleRefrigGeneration)
            - (vehicleAge, excursionFlag)
          EVIDENCE:
            stability_score: 0.74
            base_rates: {legacy_2008: 0.21, mid_2014: 0.13, modern_2020: 0.07}
        -- HV2 HYPOTHESIS END
        """;
    static final String VEHICLES_SCEPTIC_TEXT = """
        VERIFICATION:
          claim: vehicleEquipmentCohort is a single treatment (HV1)
          finding: vehicleEquipmentCohort = CONCAT(vehicleMakeModel, vehicleRefrigModel) per \
        metamodel — composed.
          verdict: ARCHAEOLOGY — NOTICED_BUT_UNREPORTED in the structured output.
        
        VERIFICATION:
          claim: vehicleRefrigGeneration reduces excursion (HV2)
          alternative_path: stratifiedGradient by generation, conditioning on vehicleAge
          per-generation residual: legacy_2008=+5pp, mid_2014=0pp, modern_2020=-4pp
          verdict: SUPPORTED
        
        COVERAGE_SUMMARY: 9 patterns; 1 archaeology, 0 contradictions.
        """;
    static final String VEHICLES_REBUTTAL_TEXT = """
        HOLD on HV1 — keeping the composed treatment in the spec; the executor's pattern \
        19 will catch it under replay and force decomposition with concrete data.
        
        HOLD on HV2 — sceptic confirmed direction within stratification; original claim stands.
        """;
    static final String HV1_COMPILER1 = """
        Compiling HV1: vehicleEquipmentCohort → excursionFlag.
        
        PipelineSpec:
          treatment: vehicleEquipmentCohort (CATEGORICAL, 8 levels)
          adjustment_set: [vehicleAge, ambientTempC, containerInsulationType, nodeRegion]
          NOTE: composed treatment per metamodel; logged in discrepancy_log.
          discrepancy_log: [{ field: treatment_form, resolution: "defer to executor structural \
        check" }]
        """;

    // HV1 — vehicleEquipmentCohort — LOOP_TO_GENERATOR → DEAD with forensic
    static final String HV1_SCEPTIC1 = """
        VERIFICATION:
          plan_item: treatment column — pattern 19 (composed treatment)
          finding: CONCAT(vehicleMakeModel, vehicleRefrigModel). Multi-column composition.
          markers: [COMPOSED_TREATMENT_VIOLATION]
          verdict: CONTRADICTED; material: true.
          compiler_note: structural defect; spec must be revised upstream.
        
        Pipeline output (informational): ATE -3.2pp on composed contrast — not interpretable.
        COVERAGE_SUMMARY: 14 patterns; 1 material structural finding.
        """;
    static final String HV1_REFINEMENT_REQUEST = """
        Your treatment vehicleEquipmentCohort = CONCAT(vehicleMakeModel, vehicleRefrigModel) \
        is composed and the compiler-sceptic flagged COMPOSED_TREATMENT_VIOLATION. Decompose \
        it: produce two per-component hypotheses (vehicleMakeModel, vehicleRefrigModel) and \
        submit whichever has the cleaner DAG seed. Treat cohort interactions as a downstream \
        discovery target. Produce your COMPLETE revised structured output addressing this.
        """;
    static final String HV1_SUPERVISOR_EXP_0 = """
        Sceptic flagged COMPOSED_TREATMENT_VIOLATION — compiler-side fix not viable, the \
        spec itself must change.""";
    static final String HV1_SUPERVISOR_NOTES_0 = """
        HV1 iter 0: composed-treatment defect. LOOP_TO_GENERATOR with refinementRequest to \
        decompose into per-component hypotheses.""";
    static final String HV1_SUPERVISOR_THINKING_0 = """
        HV1 iter 0: pattern 19 fired structural defect. Cannot recover at compile; route to \
        generator for decomposition.""";
    static final String HV1_REFINEMENT_THINKING = """
        Decomposing per supervisor request. vehicleMakeModel = chassis manufacturer (mediates \
        through age); vehicleRefrigModel = active-cooling generation (direct mechanism). \
        Picking vehicleRefrigModel as the cleaner DAG seed.""";
    static final String HV1_REFINEMENT_TEXT = """
        Decomposing HV1 per the supervisor's refinement request.
        
        -- HV1 HYPOTHESIS START (revised, decomposed)
        HYPOTHESIS:
          treatment: vehicleRefrigModel
          outcome: excursionFlag
          expected_direction: -1
          DAG_EDGES:
            - (vehicleRefrigModel, excursionFlag)
            - (vehicleAge, vehicleRefrigModel)
            - (vehicleAge, excursionFlag)
          EVIDENCE:
            stability_score: 0.69
            base_rates: {legacy: 0.21, mid: 0.13, modern: 0.07}
          NOTE: vehicleMakeModel deferred to downstream interaction discovery.
        -- HV1 HYPOTHESIS END
        """;
    static final String HV1_COMPILER2 = """
        Compiling HV1 (refined): vehicleRefrigModel → excursionFlag.
        
        PipelineSpec:
          treatment: vehicleRefrigModel (CATEGORICAL, 5 levels) — reference: legacy_2008
          outcome: excursionFlag (binary, p=0.12)
          adjustment_set: [vehicleAge, ambientTempC, containerInsulationType, nodeRegion]
          gates: { sanity: { expected_direction: -1, abort_magnitude: 0.05 } }
        """;
    static final String HV1_SCEPTIC2 = """
        VERIFICATION:
          pattern 19: vehicleRefrigModel is single source. SUPPORTED.
          pattern 2 (mediator-in-W on containerInsulationType): no path; SUPPORTED.
          pattern 16 (binary outcome feasibility): SUPPORTED.
        
        Pipeline output: ATE -0.4pp, CI [-1.8, +1.0] crosses zero, gates green.
        COVERAGE_SUMMARY: 14 patterns; 0 material findings; empirical null.
        """;
    static final String HV1_SUPERVISOR_EXP_1 = """
        Refined pipeline returned ATE -0.4pp with CI [-1.8, +1.0] crossing zero. \
        Empirical null at fleet scale; no recoverable iteration. Forensic pathologist \
        takes over.""";
    static final String HV1_SUPERVISOR_NOTES_1 = """
        HV1 iter 1: refined spec on vehicleRefrigModel passed sceptic cleanly but ATE \
        -0.4pp [-1.8, +1.0] crosses zero. PASS_THROUGH / HYPOTHESIS_DEAD; routing to \
        forensic pathologist.""";
    static final String HV1_SUPERVISOR_THINKING_1 = """
        HV1 iter 1: pipeline ran clean on the refined spec; sceptic clean; CI crosses zero. \
        Mark DEAD with forensic to characterise.""";
    static final String HV1_PATHOLOGIST_THINKING = """
        HV1 returned an empirical null on the refined spec. Diagnosing absorption: comparing \
        ATE estimates with and without containerInsulationType in W. If the effect emerges \
        when insulation is held constant, the null is dominated-mechanism, not absent.""";
    static final String HV1_PATHOLOGIST_TEXT = """
        NULL DIAGNOSIS: HV1 (vehicleRefrigModel)
        
        CLASSIFICATION: DOMINATED_MECHANISM
        
        ABSORPTION NARRATIVE:
        The vehicleRefrigModel main effect collapses when containerInsulationType is in W. \
        Re-running on the PUR-only subset (n=178000, insulation held constant at the \
        highest-excursion level) recovers an ATE of -1.6pp (CI [-2.4, -0.8]) — a real, \
        modest effect that the full-fleet specification absorbs into insulation variance.
        
        MECHANISM ASSESSMENT:
        Active refrigeration efficiency is real but dominated at fleet scale by passive \
        insulation (H1.1: -7.1pp). Once containers are well-insulated, the refrigeration \
        unit operates within thermal margins and unit-generation effect attenuates. In a \
        post-H1.1 fleet, the vehicleRefrigModel effect becomes recoverable.
        
        OPERATIONAL IMPLICATION:
        Defer fleet-wide refrigeration intervention. Re-test in the PUR-only sub-population \
        after H1.1's insulation upgrade is rolled out.
        
        CONFIDENCE: HIGH on the dominated-mechanism classification.
        """;
    static final String HV2_COMPILER = """
        Compiling HV2: vehicleRefrigGeneration → excursionFlag.
        
        PipelineSpec:
          treatment: vehicleRefrigGeneration (CATEGORICAL, 3 levels) — reference: legacy_2008
          outcome: excursionFlag
          adjustment_set: [vehicleAge, ambientTempC, nodeRegion]
          NOTE: containerInsulationType deliberately excluded — generation effect is the \
        primary estimand here, not the insulation-conditioned residual.
        """;

    // HV2 — vehicleRefrigGeneration — CLEAN_PASS (without H1 dominance, this is independent)
    static final String HV2_SCEPTIC = """
        VERIFICATION:
          plan_item: adjustment_set exclusion of containerInsulationType — pattern 4 \
        (post-treatment)
          rationale check: insulation type is determined by container, not vehicle — \
        pre-treatment relative to vehicle assignment.
          verdict: SUPPORTED — exclusion is justified for the unconditioned generation effect.
        
        VERIFICATION:
          plan_item: estimation_variants — pattern 14 (variant completeness)
          NonParamDML pair present. SUPPORTED.
        
        Pipeline output: ATE legacy→modern -3.1pp, CI [-4.2, -2.0], gates green.
        COVERAGE_SUMMARY: 12 patterns; 0 material findings.
        """;
    static final String HV2_SUPERVISOR_EXP = """
        ATE -3.1pp moving from legacy_2008 to modern_2020; CI excludes zero; sceptic clean. \
        Material direct effect of refrigeration generation. WELL_FORMED.""";
    static final String HV2_SUPERVISOR_NOTES = """
        HV2: clean run, ATE -3.1pp [-4.2, -2.0] for legacy→modern contrast. Material \
        independent effect once insulation is not in W. PASS_THROUGH / WELL_FORMED.""";
    static final String HV2_SUPERVISOR_THINKING = """
        HV2: cleanly estimated -3.1pp generation effect, gates green. WELL_FORMED.""";
    static final String HV2_ADVOCATE = """
        HV2 confirms a material vehicle-side intervention path. Moving from legacy_2008 to \
        modern_2020 refrigeration units yields a -3.1pp excursion reduction with CI excluding \
        zero. The independent estimand (without conditioning on insulation) is the relevant \
        quantity for fleet renewal decisions, since older vehicles tend to carry older \
        containers — addressing both jointly compounds the gain. Recommend coupling vehicle \
        refrigeration upgrades with the H1.1 insulation retrofit programme.
        """;
    static final String HV2_PROSECUTOR = """
        The -3.1pp effect is real but partially confounded with the H1.1 insulation finding \
        — older vehicles disproportionately carry older PUR containers. The HV1 forensic \
        confirmed that once insulation is conditioned on, the refrigeration effect attenuates \
        substantially. In an integrated rollout, the marginal value of refrigeration upgrades \
        on top of H1.1 is closer to -1.6pp than -3.1pp. Budget the integrated intervention, \
        not the standalone refrigeration estimate.
        """;
    static final String ROUTES_GEN_THINKING = """
        Anchor: routes. Stability-selecting operational/temporal treatments. Humidity \
        gradient (seasonal), dwell time (per-shipment), and route distance (per-shipment) \
        all surface as candidates with stability_score ≥ 0.55.""";

    // ─────────────────── Anchor: routes ───────────────────
    static final String ROUTES_GEN_TEXT = """
        ## Analysis Summary
        Anchor: routes. Three hypotheses surface.
        
        -- HR1 HYPOTHESIS START
        HYPOTHESIS:
          treatment: nodeAmbientHumidity
          outcome: excursionFlag
          expected_direction: +1
          DAG_EDGES:
            - (nodeAmbientHumidity, excursionFlag)
            - (nodeRegion, nodeAmbientHumidity)
            - (nodeRegion, excursionFlag)
          EVIDENCE:
            stability_score: 0.71
            breakpoint: 65% RH
        -- HR1 HYPOTHESIS END
        
        -- HR2 HYPOTHESIS START
        HYPOTHESIS:
          treatment: dwellTimeMinutes
          outcome: excursionFlag
          expected_direction: +1
          DAG_EDGES:
            - (dwellTimeMinutes, excursionFlag)
            - (nodeRegion, dwellTimeMinutes)
          EVIDENCE:
            stability_score: 0.68
            shap_curve_form: monotonic
        -- HR2 HYPOTHESIS END
        
        -- HR3 HYPOTHESIS START
        HYPOTHESIS:
          treatment: routeDistanceKm
          outcome: excursionFlag
          expected_direction: +1
          DAG_EDGES:
            - (routeDistanceKm, excursionFlag)
            - (nodeRegion, routeDistanceKm)
          EVIDENCE:
            stability_score: 0.55
            shap_curve_form: weak_monotonic
        -- HR3 HYPOTHESIS END
        """;
    static final String ROUTES_SCEPTIC_TEXT = """
        VERIFICATION:
          claim: humidity threshold at 65% RH (HR1)
          alternative_path: thresholdLocation across humidity bins
          claimedNumber: 65%; verifiedNumber: 62%
          verdict: CONDITIONAL — breakpoint shifts to 60% in summer subsample.
        
        VERIFICATION:
          claim: dwellTimeMinutes monotonic (HR2)
          alternative_path: stratifiedGradient with knot at 12-minute mark
          verdict: SUPPORTED
        
        VERIFICATION:
          claim: routeDistanceKm linear (HR3)
          alternative_path: corr(distance, outcome | dwellTime, ambientTempC)
          finding: residual correlation +0.005 once dwell + temperature conditioned on
          verdict: OVERSTATED — distance is a confound for the operational variables.
        
        COVERAGE_SUMMARY: 11 patterns; 1 conditional, 1 overstated.
        """;
    static final String ROUTES_REBUTTAL_TEXT = """
        NARROW on HR1 — accepting the seasonal heterogeneity; primary scope summer \
        (dateMonth IN 6,7,8) with 60% RH breakpoint.
        
        HOLD on HR2 — sceptic confirmed monotonic; original claim stands.
        
        HOLD on HR3 — keeping in the slate so the executor's pipeline can confirm the \
        residual signal is not recoverable; sceptic's overstated finding suggests likely \
        empirical null.
        """;
    static final String HR1_COMPILER = """
        Compiling HR1 (revised): nodeAmbientHumidity → excursionFlag, summer-scoped.
        
        PipelineSpec:
          treatment: nodeAmbientHumidity (CONTINUOUS, breakpoint 0.60 for binary variant)
          outcome: excursionFlag
          scope_filter: dateMonth IN (6, 7, 8)
          surviving_n: 142000
          adjustment_set: [nodeRegion, nodeId, ambientTempC, vehicleAge]
          estimation_variants: [
            {id: primary, treatment_form: CONTINUOUS, model_type: LinearDML},
            {id: thresh60, treatment_form: BINARY_THRESHOLD, threshold: 0.60}
          ]
        """;

    // HR1 — nodeAmbientHumidity — LOOP_TO_SCEPTIC then PASS / MARGINAL_RETURNS
    static final String HR1_SCEPTIC1 = """
        VERIFICATION:
          plan_item: adjustment_set[0] (nodeRegion) — pattern 5
          verdict: SUPPORTED
        VERIFICATION:
          plan_item: scope_filter — pattern 15
          surviving_n 141812 ≈ claimed 142000. SUPPORTED.
        
        Pipeline output: ATE +0.041 per RH unit, thresh60 ATE +2.4pp, gates green.
        COVERAGE_SUMMARY: 7 patterns; 0 material findings.
        """;
    static final String HR1_FOCUS_REQUEST = """
        Run pattern 3 (bundled variable) on adjustment_set column nodeId. The pipeline \
        metrics show within-treatment variance on nodeId clustered tightly within humidity \
        bins, and you did not run a bundled-variable check on it. Compute per-treatment-bin \
        stddev of nodeId (or its region proxy) against the population stddev. If the ratio \
        falls below 0.05, propose dropping nodeId from adjustment_set and report the \
        re-execution delta. Replace your previous review with a fresh COMPLETE one.
        """;
    static final String HR1_SUPERVISOR_EXP_0 = """
        Sceptic skipped pattern 3 on nodeId; within-humidity-bin clustering is visible in \
        the metrics and warrants a bundled-variable check before passing through.""";
    static final String HR1_SUPERVISOR_NOTES_0 = """
        HR1 iter 0: pipeline +2.4pp at humidity≥60%, sceptic clean but skipped pattern 3 on \
        nodeId. Routing LOOP_TO_SCEPTIC with focusRequest naming nodeId per-bin stddev + \
        bundled-removal re-execution.""";
    static final String HR1_SUPERVISOR_THINKING_0 = """
        HR1 iter 0: pipeline output looks plausible but sceptic skipped a pattern that's \
        clearly applicable. Route to sceptic with focused request on nodeId.""";
    static final String HR1_SCEPTIC2 = """
        VERIFICATION (re-run with supervisor focus on pattern 3):
          plan_item: adjustment_set[1] (nodeId) — pattern 3 (bundled variable)
          per-bin stddev: [19.4, 19.1, 19.6, 18.8, 19.0]; population: 21.4
          ratio min: 0.91 → not bundled.
          verdict: SUPPORTED
        
        Cross-claim: combined with iter-0 pattern 5, W matrix is well-composed. nodeId \
        spans 24-26 of 28 nodes within each humidity bucket — geography, not bundling.
        
        COVERAGE_SUMMARY: pattern 3 added; 0 material findings. ATE unchanged: +2.4pp \
        at humidity ≥ 60%.
        """;
    static final String HR1_SUPERVISOR_EXP_1 = """
        Sceptic ran pattern 3 on nodeId per the focus request and confirmed not bundled \
        (ratio 0.91 vs 0.05 BUNDLED threshold). ATE unchanged. MARGINAL_RETURNS.""";
    static final String HR1_SUPERVISOR_NOTES_1 = """
        HR1 iter 1: nodeId not bundled (per-bin stddev 19.4 of population 21.4 = 0.91 \
        ratio). ATE +2.4pp at humidity≥60% holds. PASS_THROUGH / MARGINAL_RETURNS.""";
    static final String HR1_SUPERVISOR_THINKING_1 = """
        HR1 iter 1: bundled check confirmed clean. No further loop yields material change.""";
    static final String HR1_ADVOCATE = """
        HR1 surfaces a real, scoped operational lever. Within summer months, nodes operating \
        above 60% relative humidity see a +2.4pp excursion premium with CI excluding zero. \
        The supervisor-driven re-check on nodeId confirmed the W-matrix composition is \
        sound. This translates directly to a route-planning policy: in June through August, \
        prefer nodes below the 60% RH threshold or accept the excursion premium in delivery \
        commitments.
        """;
    static final String HR1_PROSECUTOR = """
        +2.4pp is small and seasonal — about a third of H1.1's insulation effect, applying \
        only to ~28% of annual shipments. Operational re-routing is also constrained by \
        SLA windows; not all summer deliveries can be re-anchored. Annualised across the \
        full fleet, the gain is ~0.7pp — a marginal contribution next to the H1.1 retrofit. \
        Treat as an opportunistic optimisation, not a budgeted intervention.
        """;
    static final String HR2_COMPILER = """
        Compiling HR2: dwellTimeMinutes → excursionFlag.
        
        PipelineSpec:
          treatment: dwellTimeMinutes (CONTINUOUS, p25=6.2, p75=18.4)
          outcome: excursionFlag
          adjustment_set: [nodeRegion, ambientTempC, vehicleAge]
          structural_breaks: [
            {temporal_grain: 'dateWeek', column: dwellTimeMinutes, pelt_penalty: 4.79}
          ]
          estimation_variants: [
            {id: primary, treatment_form: CONTINUOUS, model_type: LinearDML},
            {id: nonparam, model_type: NonParamDML}
          ]
        """;

    // HR2 — dwellTimeMinutes — CLEAN_PASS
    static final String HR2_SCEPTIC = """
        VERIFICATION:
          plan_item: structural_breaks[0].pelt_penalty — pattern 9
          T (distinct dateWeeks) = 156; log(156) = 5.05; recomputed: 5.05; compiler stated 4.79.
          delta: 0.26 (5%); verdict: GROUNDED-CLOSE — within rounding tolerance.
        
        VERIFICATION:
          plan_item: adjustment_set — pattern 5
          verdict: SUPPORTED
        
        Pipeline output: ATE +0.06 per minute; +0.6pp per 10-minute window, CI [+0.4, +0.8].
        COVERAGE_SUMMARY: 12 patterns; 0 material findings.
        """;
    static final String HR2_SUPERVISOR_EXP = """
        ATE +0.6pp per 10-minute dwell increase with tight CI; sceptic produced no material \
        findings; gates green. WELL_FORMED.""";
    static final String HR2_SUPERVISOR_NOTES = """
        HR2: clean run, +0.6pp per 10 minutes [+0.4, +0.8]. Operationally tractable. \
        PASS_THROUGH / WELL_FORMED.""";
    static final String HR2_SUPERVISOR_THINKING = """
        HR2: pipeline cleanly estimated dwell-time effect, sceptic clean, gates green. \
        WELL_FORMED.""";
    static final String HR2_ADVOCATE = """
        HR2 is the cleanest operational lever in the run. Each 10-minute reduction in \
        dwell time at loading/unloading nodes yields a -0.6pp excursion reduction with \
        a tight CI [+0.4, +0.8]. Tightening loading windows from the current 18-minute \
        median to 12 minutes would yield an estimated -0.4pp annualised — small in \
        absolute terms but cheap to deploy via SLA enforcement. Recommend tightening \
        loading-window SLAs in the Phase-2 operational programme.
        """;
    static final String HR2_PROSECUTOR = """
        The effect is small in operational impact. -0.4pp annualised is dwarfed by H1.1's \
        -5pp projected retrofit gain. Tightening loading windows also has knock-on costs: \
        labour, scheduling rigidity, and SLA compliance overhead. The marginal value of \
        the dwell-time intervention is sensitive to how aggressively the SLA is enforced; \
        soft enforcement yields ~30% of the projected gain. Bundle with H1.1 ops planning \
        rather than budgeting separately.
        """;
    static final String HR3_COMPILER = """
        Compiling HR3: routeDistanceKm → excursionFlag.
        
        PipelineSpec:
          treatment: routeDistanceKm (CONTINUOUS)
          outcome: excursionFlag
          adjustment_set: [dwellTimeMinutes, ambientTempC, nodeRegion, vehicleAge]
          NOTE: dwellTimeMinutes included as a confounder of distance.
        """;

    // HR3 — routeDistanceKm — DEAD_NO_LOOP
    static final String HR3_SCEPTIC = """
        VERIFICATION:
          plan_item: adjustment_set inclusion of dwellTimeMinutes — pattern 2 (mediator \
        in adjustment_set)
          finding: dwellTimeMinutes is on the routeDistanceKm → excursionFlag path \
        (longer routes have more dwell). Including it in W blocks the indirect effect.
          verdict: MEDIATOR_IN_W — the residual estimand is the direct distance effect, \
        not total. Compiler's adjustment is defensible but truncates the causal pathway.
        
        Pipeline output: direct ATE +0.001 per km, CI [-0.002, +0.004] crosses zero.
        COVERAGE_SUMMARY: 9 patterns; 1 mediator-in-W finding (intentional but worth noting).
        """;
    static final String HR3_SUPERVISOR_EXP = """
        Direct ATE +0.001 per km with CI crossing zero; sceptic identified that conditioning \
        on dwellTimeMinutes blocks the indirect distance effect, leaving only the direct \
        contribution which is empirically null. The mediated effect is real but already \
        captured by HR2 (dwellTimeMinutes). HYPOTHESIS_DEAD.""";
    static final String HR3_SUPERVISOR_NOTES = """
        HR3: direct distance effect crosses zero once dwellTime is conditioned on. The \
        mediated effect is captured by HR2; no residual to claim. PASS_THROUGH / \
        HYPOTHESIS_DEAD.""";
    static final String HR3_SUPERVISOR_THINKING = """
        HR3 iter 0: direct distance effect is null; mediated effect already captured by \
        HR2. Mark DEAD; forensic pathologist takes over to characterise the mediation \
        and confirm there is no recoverable direct contribution.""";
    static final String HR3_PATHOLOGIST_THINKING = """
        HR3 returned a null direct effect with a mediator-in-W finding from the sceptic. \
        Diagnosing whether the mediation is full (dwellTime captures all distance signal) \
        or partial (residual direct effect via fuel-stop temperature exposure exists but \
        is below detection threshold).""";
    static final String HR3_PATHOLOGIST_TEXT = """
        NULL DIAGNOSIS: HR3 (routeDistanceKm)
        
        CLASSIFICATION: FULLY_MEDIATED_VIA_DWELL
        
        ABSORPTION NARRATIVE:
        Re-running the pipeline with dwellTimeMinutes removed from the adjustment_set \
        recovers a +0.018pp/km direct estimate (CI [+0.012, +0.024]) — but partitioning \
        the total effect into direct and indirect components attributes 96% to dwell time \
        and 4% to residual exposure. The 4% direct fraction is at the noise floor of the \
        per-shipment temperature sensor (±0.3°C).
        
        MECHANISM ASSESSMENT:
        Distance is a structural confound for dwell time and ambient exposure, not an \
        independent causal driver. Longer routes accumulate proportionally more loading \
        events (which dominate dwell time) and pass through more ambient zones (which \
        dominate exposure). Once dwell time is intervened on (HR2), there is no residual \
        distance effect to recover.
        
        OPERATIONAL IMPLICATION:
        Drop routeDistanceKm as a standalone treatment. The HR2 dwell-time programme \
        captures the operationally accessible portion of the distance mechanism.
        
        CONFIDENCE: HIGH on the full-mediation classification.
        """;
    static final String PEER_QUESTION_AGE_TEMPORAL = """
        I am verifying the H1.2 PipelineSpec for containerAge. The compiler did not \
        include year-fixed-effects in the adjustment_set. Before I run pattern 9 \
        (temporal confounding), I want to confirm: does the data span overlap with the \
        period where shipment volume changed materially? If yes, the year-fixed-effects \
        omission could be biasing the age effect.
        """;

    // ─────────────────── Peer Q&A pairs ───────────────────
    static final String PEER_ANSWER_AGE_TEMPORAL = """
        Yes — the data spans 2019-2024. Shipment volume grew ~40% between 2020 and 2022 \
        following a logistics network expansion, then flattened. Container commissioning \
        also concentrated in 2021-2022 to support the volume growth, so commissioning \
        year and shipment year are correlated. Year-fixed-effects are mechanically \
        well-motivated for any treatment that touches container age.
        """;
    static final String PEER_QUESTION_HUMIDITY_GRAIN = """
        I am verifying the HR1 PipelineSpec adjustment_set. The compiler included \
        nodeId as a confounder under summer scope. Before I run the bundled-variable \
        check on nodeId, I want to confirm: is nodeAmbientHumidity measured per-shipment \
        from the installed sensor, or is it a node-month aggregate joined back? The \
        grain matters for whether nodeId can co-vary with treatment within a single \
        shipment.
        """;
    static final String PEER_ANSWER_HUMIDITY_GRAIN = """
        Per-shipment, captured by the installed sensor. The data feed samples humidity \
        hourly at the node and the shipment record carries the mean over the dwell window \
        (typically 8-14 hours). Same temporal grain as the outcome. nodeId-level variation \
        in humidity within a shipment is bounded by sensor noise (~1.5% RH) — well below \
        the 5pp humidity bin width the threshold variant uses, so co-variation between \
        nodeId and treatment within a shipment is not a concern.
        """;

    private MockSwarmContent() {
    }
}
