package com.rorm.client.ai;

public interface GenPhaseDump {

    String GEN = """
        Now I have comprehensive results from the full iteration chain. Let me compile the final analysis.
        
        ## Column Classification
        
        **Anchor-Internal (containers + vehicles + fleet_transitions + maintenance_log + nodes + node_ops_logs + loggers):**
        - `containerInsulationType` — container insulation material (categorical, 4 levels)
        - `containerAgeMonths` — container age at shipment (continuous)
        - `vehicleInsulationRating` — vehicle body insulation quality (continuous, 0.70-1.00)
        - `vehicleRefrigAgeMonths` — reefer unit age (continuous, 0-101 months)
        - `vehicleRefrigModel` — reefer model (categorical proxy, 9 levels)
        - `vehicleMakeModel` — vehicle chassis model (categorical proxy, 9 levels)
        - `vehicleReeferKwRated` — cooling capacity (continuous, 3.5-7.0 kW)
        - `vehicleCargoVolumeM3` — cargo volume (continuous, 8-20 m³)
        - `nodeRefrigHealthPct` — facility refrigeration health (continuous, 0-100%)
        - `nodePowerStatus` — facility power status (binary: normal/outage)
        
        **Anchor-External (confounders to saturate):**
        - `preDepartureTempC` — near-outcome mediator for facility-phase pathway; safe to saturate for transit-phase analysis
        - `ambientTempAtDispatchC`, `ambientTempAtArrivalC` — exogenous climate mechanism
        - `receivingDelayMin`, `receivingDockTempControlled`, `isAfterHoursArrival` — receiving operations
        - `siteType`, `siteUrbanRural` — destination site characteristics
        - `stopSequence`, `palletPosition` — route/loading operations (correlated group r=0.94)
        - `routeTotalStops`, `routeTotalDriveHours`, `routeTotalAirMiles` — route characteristics
        - `dayOfWeek`, `dispatchHour` — temporal mechanism
        - `productClass`, `productMassAtStopKg` — product characteristics
        - `isHosRegulated` — regulatory classification
        
        **Excluded:**
        - `disposition`, `rejectionReason`, `acceptanceStatus` — POST-OUTCOME
        - `reroutedFlag` — POST-OUTCOME (confirmed: 100% excursion rate among rerouted)
        - `maxTempObservedC` — POST-OUTCOME (temperature measurement during/after excursion)
        - `vehicleBreakdown` — DEAD FIELD (all false)
        - `loggerId`, `loggerMonthsSinceCal` — MEASUREMENT METADATA
        - All ID/key fields — used for joins only
        
        ---
        
        ## Iteration Chain Summary
        
        | Run | Target | Saturated | Top Features | Action |
        |-----|--------|-----------|--------------|--------|
        | **Run 1** | excursionFlag | None | preDepartureTempC (1.0), receivingDelayMin (1.0), receivingDockTempControlled (0.99), stopSequence (0.79) | preDepartureTempC = near-outcome mediator. Classify external mechanisms. |
        | **Run 2a** | excursionFlag | External + preDepartureTempC | vehicleRefrigModel (1.0), vehicleMakeModel (1.0), containerInsulationType (1.0), vehicleReeferKwRated (0.67) | Categoricals dominate → equipment cohort proxies. Saturate to unbundle. |
        | **Run 2b** | **preDepartureTempC** (FLIP) | None | ambientTempAtDispatchC (1.0), nodeRefrigHealthPct (0.67), nodePowerStatus (0.67), containerAgeMonths (0.33) | Facility cooling mechanism confirmed. |
        | **Run 3** | excursionFlag | External + preDep + categoricals | containerInsulationType (1.0), vehicleInsulationRating (0.71), vehicleCargoVolumeM3 (0.67), nodePowerStatus (0.38) | Continuous equipment properties surface. Final anchor-internal treatments identified. |
        
        ---
        
        ```
        HYPOTHESIS:
          treatment: containerInsulationType
          treatment_scope: containers (anchor entity)
          treatment_form: categorical
          outcome: excursionFlag
          expected_direction: VIP_panel → lowest excursion; PIR_foam → highest excursion
          effect_modifiers: [ambientTempAtDispatchC, vehicleInsulationRating]
          independently_actionable_mediators: []
        
          ANCHOR_GROUNDING:
            anchor_entity: containers
            reachable_enrichment: [vehicles, fleet_transitions, maintenance_log, cold_nodes, node_ops_logs, loggers]
            iteration_chain:
              - run_1: {target: excursionFlag, saturated: [], top_feature: preDepartureTempC,
                        classification: A (near-outcome mediator), mechanism: facility_pre_departure_temperature,
                        action: saturate alongside all external mechanisms}
              - run_2a: {target: excursionFlag,
                         saturated: [preDepartureTempC, ambientTempAtDispatchC, ambientTempAtArrivalC,
                                    receivingDelayMin, receivingDockTempControlled, isAfterHoursArrival,
                                    siteType, siteUrbanRural, stopSequence, palletPosition, routeTotalStops,
                                    routeTotalDriveHours, routeTotalAirMiles, dayOfWeek, dispatchHour,
                                    productClass, productMassAtStopKg, isHosRegulated],
                         top_feature: vehicleRefrigModel, classification: D (anchor-internal proxy),
                         mechanism: equipment_cohort_proxy, action: saturate to unbundle components}
              - run_3: {target: excursionFlag,
                        saturated: [all externals + preDepartureTempC + vehicleRefrigModel + vehicleMakeModel],
                        top_feature: containerInsulationType, classification: D (anchor-internal actionable),
                        mechanism: container_thermal_barrier, action: SHAP → hypothesis}
            saturated_mechanisms:
              - mechanism: climate_geography
                features_saturated: [ambientTempAtDispatchC, ambientTempAtArrivalC]
                reason: exogenous
              - mechanism: receiving_operations
                features_saturated: [receivingDelayMin, receivingDockTempControlled, isAfterHoursArrival, siteType, siteUrbanRural]
                reason: anchor-external
              - mechanism: route_dispatch
                features_saturated: [stopSequence, palletPosition, routeTotalStops, routeTotalDriveHours, routeTotalAirMiles]
                reason: anchor-external
              - mechanism: temporal
                features_saturated: [dayOfWeek, dispatchHour]
                reason: exogenous
              - mechanism: product
                features_saturated: [productClass, productMassAtStopKg, isHosRegulated]
                reason: anchor-external
              - mechanism: pre_departure_temperature
                features_saturated: [preDepartureTempC]
                reason: mediator (facility-phase) — safe to saturate for transit-phase analysis
              - mechanism: equipment_cohort_proxy
                features_saturated: [vehicleRefrigModel, vehicleMakeModel]
                reason: proxy — categoricals bundling age + specs + deployment; saturated to surface decomposed components
        
          DAG_EDGES:
            - (containerInsulationType, excursionFlag)  # Different insulation types have different thermal conductivity, directly affecting heat ingress rate during transit
            - (ambientTempAtDispatchC, excursionFlag)   # Ambient heat load drives all thermal excursion risk
            - (ambientTempAtDispatchC, preDepartureTempC) # Ambient temp affects facility cooling effectiveness
            - (preDepartureTempC, excursionFlag)        # Starting temperature determines thermal buffer for transit
            - (containerInsulationType, preDepartureTempC) # Minor path: insulation type affects facility-phase cooling retention
        
          CONFOUNDER_REASONING:
            ambientTempAtDispatchC: Exogenous climate variable. Affects both the rate of heat ingress through the container
              (hotter ambient = faster heat transfer) AND the distribution of insulation types deployed (hotter regions may
              receive different container allocations). Classic confounder — upstream of both treatment and outcome.
            preDepartureTempC: Mediator between facility conditions and outcome. Saturated to isolate the direct transit-phase
              effect of container insulation. Safe to saturate because container insulation type does not CAUSE pre-departure
              temperature through a major pathway — pre-departure temp is primarily determined by facility refrigeration.
        
          EVIDENCE:
            stability_score: Run 3 — sel_freq 1.0 across ALL 3 model families (linear, elastic_net, lightgbm), rank 1.0
            shap_curve_form: categorical
            shap_details:
              - Run 2a SHAP (saturated): PUR_foam |SHAP|=0.59, PIR_foam=0.55, VIP_panel=0.52, XPS_foam=0.49
              - All categories have low std relative to mean (std ~0.02) — stable effects
            breakpoint: N/A (categorical treatment)
            domain_alignment: PARTIALLY ALIGNED. Domain ranking: VIP > PIR ≈ PUR > XPS.
              Data ranking by excursion rate: VIP (6.09%) < PUR (6.34%) < XPS (6.76%) < PIR (6.95%).
              VIP is confirmed best. PIR performs WORSE than PUR and XPS in the field, despite
              nominally similar or better lab thermal conductivity — possibly due to moisture
              sensitivity or older container age distribution in the PIR fleet.
        
          DOMAIN_RANKING:
            ranking: [PIR_foam, XPS_foam, PUR_foam, VIP_panel]  # worst to best by data
            expected_ratio: VIP 3-7× better thermal conductivity than foams
            source: Domain knowledge — VIP panel thermal conductivity 3-7 mW/m·K vs PUR 20-24 vs XPS 28-35 W/m·K
        
          MEASUREMENT_METADATA:
            [loggerMonthsSinceCal, loggerId]
        ```
        
        ```
        HYPOTHESIS:
          treatment: vehicleInsulationRating
          treatment_scope: vehicles (reachable enrichment)
          treatment_form: binary_threshold
          threshold_value: 0.762
          threshold_convergence: 49/50
          outcome: excursionFlag
          expected_direction: -1 (higher insulation rating → lower excursion risk)
          effect_modifiers: [ambientTempAtDispatchC, containerInsulationType, vehicleReeferKwRated]
          independently_actionable_mediators: []
        
          ANCHOR_GROUNDING:
            anchor_entity: containers
            reachable_enrichment: [vehicles, fleet_transitions, maintenance_log, cold_nodes, node_ops_logs, loggers]
            iteration_chain:
              - run_1: {target: excursionFlag, saturated: [], top_feature: preDepartureTempC,
                        classification: A, mechanism: pre_departure_temperature, action: saturate}
              - run_2a: {target: excursionFlag, saturated: [externals + preDepartureTempC],
                         top_feature: vehicleRefrigModel, classification: D (proxy),
                         mechanism: equipment_cohort_proxy, action: saturate to unbundle}
              - run_3: {target: excursionFlag,
                        saturated: [all externals + preDepartureTempC + vehicleRefrigModel + vehicleMakeModel],
                        top_feature: containerInsulationType (#1), vehicleInsulationRating (#2 at sel_freq 0.71),
                        classification: D (anchor-internal actionable),
                        mechanism: vehicle_body_thermal_envelope, action: SHAP → hypothesis}
            saturated_mechanisms: [same as Hypothesis 1]
        
          DAG_EDGES:
            - (vehicleInsulationRating, excursionFlag)  # Vehicle body insulation determines cargo compartment temperature during transit; degraded insulation allows more ambient heat ingress
            - (ambientTempAtDispatchC, excursionFlag)
            - (ambientTempAtDispatchC, vehicleInsulationRating) # Hotter regions may receive vehicles with different insulation — deployment confounding
            - (vehicleRefrigModel, vehicleInsulationRating) # Each vehicle model comes with specific insulation specs — controlled for via saturation
        
          CONFOUNDER_REASONING:
            vehicleRefrigModel: Controls for the fact that different vehicle models come with different baseline
              insulation ratings. By saturating the model categorical, we isolate WITHIN-MODEL insulation variation
              from between-model differences.
            ambientTempAtDispatchC: Higher ambient temps both stress insulation more AND are correlated with
              regional deployment patterns. Saturated as exogenous confounder.
        
          EVIDENCE:
            stability_score: Run 3 — sel_freq 0.71, avg_rank 3.43; Run 1 — nonlinear candidate (tree rank 12, linear rank 20)
            shap_curve_form: binary_threshold
            breakpoint:
              - Run 2a SHAP (transit-phase, saturated): 0.762 (IQR 0.758-0.767), 49/50 converged — STRONG
              - Run 1 SHAP (total effect, unsaturated): 0.865 (IQR 0.847-0.884), 49/50 converged
              - The two breakpoints reflect different causal estimands: 0.865 is total effect (including mediated
                paths through pre-departure temp), 0.762 is direct transit-phase effect only
            domain_alignment: ALIGNED. Domain: vehicle insulation degrades 3-5% per year; lower rating → higher risk.
              Data confirms: below 0.762 threshold, excursion risk increases substantially.
            domain_discrepancy: The TOTAL effect breakpoint (0.865 from Run 1 SHAP) was confounded by deployment —
              well-insulated vehicles deployed to hot regions showed spurious positive association. After controlling
              for ambient temperature (Run 2a), the correct direction emerged with a LOWER threshold (0.762).
        
          MEASUREMENT_METADATA:
            [loggerMonthsSinceCal, loggerId]
        ```
        
        ```
        HYPOTHESIS:
          treatment: nodeRefrigHealthPct
          treatment_scope: cold_nodes (reachable enrichment, via node_ops_logs)
          treatment_form: binary_threshold
          threshold_value: 80
          threshold_convergence: 0/50 (no Muggeo breakpoint converged — extreme tail distribution)
          outcome: excursionFlag
          expected_direction: -1 (higher refrigeration health → lower excursion risk)
          effect_modifiers: [ambientTempAtDispatchC, nodePowerStatus]
          independently_actionable_mediators: [preDepartureTempC]
        
          ANCHOR_GROUNDING:
            anchor_entity: containers
            reachable_enrichment: [vehicles, fleet_transitions, maintenance_log, cold_nodes, node_ops_logs, loggers]
            iteration_chain:
              - run_2b: {target: preDepartureTempC (FLIP TARGET), saturated: [],
                         top_feature: ambientTempAtDispatchC (#1), nodeRefrigHealthPct (#2),
                         classification: D (anchor-internal actionable),
                         mechanism: facility_cooling_effectiveness, action: SHAP → hypothesis}
              - run_3: {target: excursionFlag,
                        saturated: [all externals + preDepartureTempC + categoricals],
                        nodeRefrigHealthPct ranking: sel_freq 0.04, avg_rank 5.18,
                        note: "low sel_freq because effect operates entirely through preDepartureTempC mediator"}
            saturated_mechanisms: [same as Hypothesis 1 for Run 3]
        
          DAG_EDGES:
            - (nodeRefrigHealthPct, preDepartureTempC)  # Degraded facility refrigeration → higher product temperatures before dispatch
            - (preDepartureTempC, excursionFlag)        # Higher pre-departure temp → less thermal buffer → more excursions
            - (ambientTempAtDispatchC, nodeRefrigHealthPct) # Extreme ambient heat strains facility cooling systems, reducing effective health
            - (ambientTempAtDispatchC, preDepartureTempC)   # Direct ambient heating effect on facility internal temperatures
        
          CONFOUNDER_REASONING:
            ambientTempAtDispatchC: Extreme ambient heat both degrades facility refrigeration effectiveness AND
              directly raises internal facility temperatures. This dual pathway makes it a confounder of the
              nodeRefrigHealthPct → preDepartureTempC link.
            The nodeRefrigHealthPct → preDepartureTempC → excursionFlag pathway is FULLY MEDIATED. When
              preDepartureTempC is saturated (Run 3), nodeRefrigHealthPct drops to sel_freq 0.04 — confirming
              the indirect path. When preDepartureTempC is the TARGET (Run 2b), nodeRefrigHealthPct ranks #2.
        
          EVIDENCE:
            stability_score: Run 2b (FLIP TARGET) — sel_freq 0.67, rank 3.33 across linear/elastic_net models
            shap_curve_form: extreme_tail_only
            shap_details:
              - Run 1 SHAP: 1 bin at health=50, tail_sensitivity=100.5× — effect concentrated at extreme low values
              - Run 2a SHAP: 1 bin, tail_sensitivity=3.7× — still extreme-only after saturation
              - Exploratory data: nodeRefrigHealthPct <80% → 44.2% excursion rate (n=4,411) vs 6.2% at 100% (n=557,390)
            breakpoint: No formal Muggeo breakpoint converged (0/50) because 99% of observations sit at 100%.
              Empirical threshold set at 80% based on exploratory excursion rate analysis showing 7× risk increase below this level.
            domain_alignment: ALIGNED. Degraded facility refrigeration directly impairs product pre-cooling.
        
          MEASUREMENT_METADATA:
            [loggerMonthsSinceCal, loggerId]
        ```
        
        ```
        RARE_EVENT_FINDING:
          feature: nodePowerStatus
          rare_level: outage
          n: 256
          pct_of_total: 0.045%
          outcome_rate_at_rare: 79.3%
          outcome_rate_baseline: 6.5%
          rate_ratio: 12.2×
          anchor_internal: yes (cold_nodes / node_ops_logs entities)
          mechanism: Power outage disables facility refrigeration entirely, causing product temperatures to rise
            rapidly above 8°C. With 0% refrigeration health during outage (confirmed: avg nodeRefrigHealthPct=0
            during outage shipments), products start transit already above threshold.
          tautology_check: NOT tautological — power outage is a pre-departure infrastructure event, not a
            consequence of the excursion. The temporal ordering is: outage occurs → facility temp rises →
            product dispatched warm → excursion detected during transit.
          conditional_context:
            - condition: cold_nodes.hasRedundancy checked for mitigation
              finding: 11/28 nodes (39%) have redundancy. Cannot determine from this data whether
                redundant nodes had lower outage-excursion rates — the nodePowerStatus field on shipments
                doesn't cross-reference redundancy. Recommend analysis matching node IDs to redundancy status.
          prescriptive_implication: Install backup power / redundant refrigeration at all cold nodes.
            Prioritize nodes in hot climate regions (Southwest_AZ has 4 nodes, 2 without redundancy).
          downstream_action: VERIFY_AND_PRESCRIBE
        ```
        
        ```
        RARE_EVENT_FINDING:
          feature: nodeRefrigHealthPct
          rare_level: <80%
          n: 4,411
          pct_of_total: 0.78%
          outcome_rate_at_rare: 44.2%
          outcome_rate_baseline: 6.5%
          rate_ratio: 6.8×
          anchor_internal: yes (cold_nodes / node_ops_logs entities)
          mechanism: Severely degraded facility refrigeration (below 80% health) cannot maintain proper
            cold storage temperatures, causing products to warm before dispatch. This creates high
            pre-departure temperatures that overwhelm transit thermal protection.
          tautology_check: NOT tautological — refrigeration health is measured at the facility level before
            the shipment enters transit.
          conditional_context:
            - condition: Distribution across regions
              finding: Need to verify if degraded health events concentrate in specific nodes/regions.
                Older nodes (refrigSystemAgeMonthsAtStart up to 98 months) may be more prone to degradation.
          prescriptive_implication: Implement preventive maintenance thresholds for facility refrigeration —
            halt dispatches or flag shipments when nodeRefrigHealthPct drops below 80%.
          downstream_action: VERIFY_AND_PRESCRIBE
        ```
        
        ```
        INTERACTION_CANDIDATE:
          components: [containerInsulationType, vehicleInsulationRating]
          component_hypotheses: [Hypothesis_1, Hypothesis_2]
          joint_signal_evidence: Both features independently predict excursion through the same physical
            mechanism (thermal barrier during transit). Domain knowledge indicates the two insulation layers
            (container + vehicle body) interact multiplicatively — total thermal resistance is the sum of
            both barriers. A VIP container in a well-insulated vehicle has additive protection, while a
            PIR container in a poorly insulated vehicle faces compounded vulnerability. The nonlinear
            candidates list shows vehicleInsulationRating has tree_rank=12 vs linear_rank=20 in Run 1,
            suggesting interaction structure that linear models miss.
          expected_interaction_form: synergistic
          reasoning: Thermal protection is a chain of barriers. When both are strong, each provides
            independent protection against ambient heat. When both are weak, the compounding effect is
            worse than either alone — a poorly insulated vehicle allows more heat into the cargo area,
            and a poor container then fails to protect the product from that elevated compartment temp.
            This creates a synergistic interaction where the joint effect exceeds the sum of individual effects.
        ```
        
        ```
        INTERACTION_CANDIDATE:
          components: [containerInsulationType, ambientTempAtDispatchC]
          component_hypotheses: [Hypothesis_1]
          joint_signal_evidence: containerInsulationType SHAP has high std relative to mean (e.g., PIR_foam:
            std=0.33 vs mean_abs_shap=0.054 in Run 1, and std=0.022 vs mean=0.55 in Run 2a). The high
            std in Run 1 (before saturation) suggests the insulation type effect varies substantially across
            ambient conditions. Domain knowledge strongly predicts this: insulation matters most when
            ambient temperature is high (>30°C), and matters little in mild conditions.
          expected_interaction_form: regime_change
          reasoning: At low ambient temperatures (winter, mild regions), all container types maintain 2-8°C
            regardless — the effect is near zero. At high ambient temperatures (summer, AZ/TX), the
            differential thermal conductivity between VIP and PIR foam creates a large performance gap.
            The interaction is regime-dependent: insulation type only matters above an ambient temperature
            threshold (~25-30°C).
        ```
        
        ```
        BELOW_DETECTION_THRESHOLD:
          feature: vehicleRefrigAgeMonths
          anchor_entity: vehicles (reachable enrichment)
          ss_rank_after_saturation: Run 3 — avg_rank 5.29, sel_freq 0.0
          selection_frequency: 0.0 in all three post-saturation runs (Run 2a, Run 3)
          domain_expectation: WELL_ESTABLISHED — reefer unit performance degrades after 5-7 years (60-84 months),
            with compressor inefficiency, refrigerant leaks, condenser fouling. Expected monotonic increase in
            excursion risk with age.
          absence_hypothesis: screening
          absence_reasoning: The age effect is entirely mediated through the equipment cohort categoricals
            (vehicleRefrigModel, vehicleMakeModel). Each model corresponds to a specific fleet generation
            and age range (confirmed: Daikin_ZeSTIA/Carrier_Vector_HE_19/TK_Advancer_A400 are Gen3 = <30 months;
            Carrier_Supra_860/TK_T-680Pro are Gen1/2 = older). Once model is controlled, WITHIN-MODEL age
            variation is too small to detect relative to the between-model differences in specs.
            Additionally, raw age data shows 80+ months at 15.1% excursion (vs 6.3% baseline), but this
            signal is fully absorbed by the vehicle insulation rating degradation associated with older units.
            The SHAP curve from Run 2a shows a subtle 50/50 converged breakpoint at 13.9 months but with
            very small absolute SHAP magnitude (max 0.03) — the effect exists but is operationally negligible
            once other variables are controlled.
          recommended_downstream:
            analysis_type: GRF_heterogeneity
            proposed_modifiers: [ambientTempAtDispatchC, vehicleRefrigModel, vehicleInsulationRating]
            rationale: The age effect may be conditional on ambient temperature (degraded reefers fail
              disproportionately in hot conditions) and may only manifest strongly within specific models.
              A GRF with ambient temperature as the moderating variable could reveal a conditional age
              effect that is diluted at the population level.
        ```
        
        ```
        BELOW_DETECTION_THRESHOLD:
          feature: containerAgeMonths
          anchor_entity: containers (anchor entity)
          ss_rank_after_saturation: Run 3 — avg_rank 7.13, sel_freq 0.0
          selection_frequency: 0.0 in all post-saturation runs; 0.33 in FLIP TARGET run (tree-only)
          domain_expectation: DOCUMENTED — PUR/XPS foams lose 5-15% R-value over 5 years; VIP panels risk
            vacuum degradation. Expected monotonic increase in excursion risk with age.
          absence_hypothesis: screening + conditional_effect
          absence_reasoning: Two mechanisms explain the weak signal:
            (1) SCREENING by containerInsulationType: Once insulation TYPE is controlled (VIP vs PIR vs PUR vs XPS),
            within-type age variation produces minimal additional signal. The type difference dominates the age
            degradation within each type.
            (2) CONDITIONAL EFFECT: The FLIP TARGET analysis (Run 2b) showed containerAgeMonths ranked #1 in
            LightGBM for predicting preDepartureTempC (nonlinear candidate). This means container age DOES affect
            facility-phase cooling (older containers lose heat faster during storage), but this effect operates
            entirely through preDepartureTempC. Once preDepartureTempC is saturated, the age signal disappears.
            The raw data shows a non-monotonic pattern (0-20: 7.2%, 20-40: 5.1%, 40-60: 7.6%, 80+: 4.5%),
            suggesting deployment confounding overwhelms the degradation signal.
          recommended_downstream:
            analysis_type: GRF_heterogeneity
            proposed_modifiers: [containerInsulationType, ambientTempAtDispatchC]
            rationale: Container age degradation may matter most for specific insulation types (VIP panels may
              lose vacuum integrity at specific age thresholds, while PUR foam degrades continuously). A GRF
              stratified by insulation type could reveal type-specific age effects invisible at the population level.
        ```
        
        ```
        DOMAIN_DISCREPANCY:
          expected_driver: Equipment aging (vehicleRefrigAgeMonths, containerAgeMonths) as continuous degradation
          domain_evidence_strength: WELL_ESTABLISHED
          empirical_finding: After controlling for equipment model categoricals, ambient temperature, and
            pre-departure temperature, neither age variable shows meaningful stability selection signal
            (both sel_freq 0.0 in final iteration). Raw age gradient exists (15.1% excursion at 80+ months
            reefer age vs 6.3% baseline) but is fully absorbed by model/specs confounding.
          ss_rank: vehicleRefrigAgeMonths: Run 3 rank 5.29; containerAgeMonths: Run 3 rank 7.13
          absence_hypothesis: screening
          reasoning: The age effect in this fleet is NOT absent — it manifests as BETWEEN-MODEL differences
            rather than WITHIN-MODEL degradation. The fleet uses 3 generations of vehicles. Gen1 vehicles
            (older Carrier_Supra_860, Daikin_RKN) have worse insulation ratings AND are older. The combined
            effect shows up as a model categorical effect rather than a continuous age effect. The residual
            within-model age variation spans only ~30-40 months (insufficient range for degradation to
            manifest). Additionally, survivorship bias operates: 191 vehicles have been retired (64% of fleet),
            likely removing the worst-performing old units from the observable population.
          proposed_resolution:
            method: Include retired vehicles in historical analysis; compute per-vehicle degradation trajectory
              (excursion rate vs age within vehicle) to estimate within-unit aging effects. Alternatively,
              analyze fleet_transitions data to check whether pre-retirement excursion rates were elevated.
            expected_result_if_real: Within-vehicle trajectories should show accelerating excursion rates
              after 5-7 years, even within the same model cohort. Pre-retirement excursion rates should
              exceed cohort averages.
            expected_result_if_spurious: Within-vehicle trajectories show flat or random excursion rates
              regardless of age, and pre-retirement rates are no different from cohort baseline.
        ```
        
        ```
        DOMAIN_DISCREPANCY:
          expected_driver: Container insulation type ranking — domain expects XPS to be worst performer
          domain_evidence_strength: WELL_ESTABLISHED
          empirical_finding: PIR_foam shows HIGHEST excursion rate (6.95%) despite domain expectation
            that PIR (polyisocyanurate) has better thermal conductivity than XPS (extruded polystyrene).
            Empirical ranking: PIR (worst) > XPS > PUR > VIP (best).
            Domain ranking: XPS (worst) > PIR ≈ PUR > VIP (best).
          ss_rank: containerInsulationType rank 1.0 in Run 3
          absence_hypothesis: confounding + measurement_resolution
          reasoning: Two plausible explanations:
            (1) PIR containers in this fleet may be older on average (PIR was a common early-generation
            insulation choice), leading to greater cumulative degradation. PIR is also more susceptible
            to moisture uptake in humid climates, which degrades its thermal performance over time.
            (2) The PIR fleet segment may be deployed in harder conditions (hotter routes, smaller vehicles)
            creating confounding that inflates its observed excursion rate.
          proposed_resolution:
            method: Stratify PIR excursion rate by container age AND hub region. Check whether PIR
              containers are systematically older or deployed to hotter hubs. Compute age-adjusted
              insulation-type comparisons within climate zones.
            expected_result_if_real: PIR containers show elevated excursion rates even after age and
              region stratification — would indicate genuine field performance issues (moisture uptake,
              manufacturing quality).
            expected_result_if_spurious: PIR excursion rate normalizes after controlling for age and
              deployment region — would indicate the raw ranking reflects confounding, not inherent
              material performance.
        ```
        └── generator (7472) done
        """;

    String SCEPTIC = """
        Excellent. Now I have comprehensive results. Let me compile the final verification output.
        
        ---
        
        ## VERIFICATION RESULTS
        
        ---
        
        VERIFICATION:
          plan_item: H1 — Pattern 3 (Treatment Direction)
          claim: containerInsulationType ranking: VIP_panel (6.09%) best → PIR_foam (6.95%) worst
          generator_evidence: Run 3 sel_freq 1.0, rank 1.0; marginal excursion rates by type
          alternative_path: Stratified by region (Southwest_AZ single hub), by hot conditions (>30°C), and by controlled band (25-30°C + low vehicle insulation)
          generator_number: VIP 6.09% vs PIR 6.95% (0.86pp spread)
          skeptic_number: In hot conditions (>30°C): VIP 32.9% WORST, PUR 28.2% BEST. In Southwest_AZ only: VIP 17.1% WORST, PUR 10.6% BEST. In controlled band (25-30°C, low vehicle insulation): VIP 11.0% BEST, PUR 14.2% WORST — VIP advantage returns with 2.3pp advantage over PIR.
          delta: The marginal ranking REVERSES in hot conditions and within the hottest hub. VIP is deployed disproportionately to PacificNW_OR (21.8% of VIP shipments vs 5.6% of PIR), where excursion rates are ultra-low (1.4%). This favorable deployment inflates VIP's marginal rate.
          pattern: 3 (Treatment Direction) + 6 (Ecological Fallacy)
          verdict: CONDITIONAL
          material: yes
          executor_note: The claimed direction (VIP best) holds ONLY after controlling for both ambient temperature AND vehicle insulation deployment. The marginal numbers (6.09% vs 6.95%) cited in the output are confounded by deployment allocation and do not represent causal effects. The true within-stratum VIP advantage (~2.3pp) is actually LARGER than the marginal spread, but occurs only in moderate-hot conditions. In mild conditions (<20°C), all types perform near-identically (~0.1-0.2% excursion). H1 should be narrowed to state that VIP advantage is regime-dependent and concentrated in hot conditions, not a universal property.
        
        ---
        
        VERIFICATION:
          plan_item: H2 — Pattern 3 (Treatment Direction) + Threshold
          claim: vehicleInsulationRating threshold at 0.762 with direction -1 (higher rating → lower excursion)
          generator_evidence: Run 2a SHAP breakpoint 0.762 (IQR 0.758-0.767), 49/50 converged; Run 3 sel_freq 0.71
          alternative_path: thresholdLocation in 3 conditions (unconditional, preDep-controlled, hot), ecological fallacy within regions, cross-tab with container insulation showing avg ambient per cell
          generator_number: Threshold 0.762, direction -1
          skeptic_number: Unconditional: inflection at 0.908 (wrong threshold, positive direction). Hot conditions (>30°C): inflection at 0.723 but NO monotonic pattern — bin rates: 0.709=20.7%, 0.737=35.1%, 0.931=39.3%. PreDep 5-7°C: inflection at 0.943, all rates <1% (no detectable pattern). Ecological fallacy check: within-region slope POSITIVE (+0.144), matching between-region (+0.126). Cross-tab: for EVERY container insulation type, high-vehicle-insulation group has HIGHER excursion than low, driven by 0.6-2.0°C higher avg ambient exposure.
          delta: Claimed threshold 0.762 not detected in raw data under any conditioning. Direction consistently POSITIVE (opposite of claimed -1) across all stratifications.
          pattern: 3 (Treatment Direction) + Threshold + 6 (Ecological Fallacy)
          verdict: OVERSTATED
          material: yes
          executor_note: The direction reversal persists even WITHIN regions (ecological fallacy check: positive slope both within and between groups). The SHAP breakpoint at 0.762 may correctly identify a conditional effect after multivariate saturation, but this effect cannot be reproduced via any bivariate or stratified raw-data analysis. The deployment confounding (well-insulated vehicles → hot regions) is so pervasive it penetrates even within-hub strata. Either (1) the SHAP model captures a genuine but very fragile conditional signal, or (2) the model learned a deployment correlation that even saturation didn't fully remove. H2 needs much stronger identification strategy before being actionable.
        
        ---
        
        VERIFICATION:
          plan_item: H3 — Pattern 1 (Screening/Mediation)
          claim: nodeRefrigHealthPct effect is FULLY MEDIATED through preDepartureTempC (sel_freq drops from 0.67 to 0.04 when preDep saturated)
          generator_evidence: Run 2b sel_freq 0.67 for predicting preDepartureTempC; Run 3 sel_freq 0.04 for excursionFlag
          alternative_path: verifyScreeningMediation computing corr(health, outcome), corr(health, preDep), partial_corr(health, outcome | preDep)
          generator_number: "FULLY MEDIATED" (sel_freq 0.04 implies near-zero residual)
          skeptic_number: corr(health, outcome) = -0.149; corr(health, preDep) = -0.136; partial_corr(health, outcome | preDep) = -0.083. Reduction = 44% (from -0.149 to -0.083).
          delta: Partial correlation of -0.083 remains after controlling for mediator. Only 44% of signal is mediated, not "fully".
          pattern: 1 (Screening/Mediation)
          verdict: INCOMPLETE
          material: yes
          executor_note: The mediation is PARTIAL, not full. After controlling for preDepartureTempC, nodeRefrigHealthPct retains substantial residual correlation with excursionFlag (-0.083). This means degraded node refrigeration affects excursions through ADDITIONAL pathways beyond pre-departure temperature (e.g., temperature instability during loading, cooling rate after door openings, or quality of pre-cooling process beyond the final measured temp). H3 should acknowledge a direct path (nodeRefrigHealthPct → excursionFlag) in addition to the mediated path. The sel_freq drop from 0.67 to 0.04 in stability selection may reflect model-based attenuation rather than true full mediation.
        
        ---
        
        VERIFICATION:
          plan_item: BDT1 — Patterns 2 + 5 (Proxy Absorption + Absence)
          claim: vehicleRefrigAgeMonths absorbed by vehicleRefrigModel categorical; sel_freq 0.0 after saturation
          generator_evidence: Run 3 sel_freq 0.0, avg_rank 5.29; "within-model age variation too small to detect"
          alternative_path: verifyProxyAbsorption (age within each reefer model), verifyAbsence in hot conditions, direct within-Daikin_RKN age-bucket analysis
          generator_number: sel_freq 0.0 (effectively zero signal)
          skeptic_number: Proxy absorption INCOMPLETE — 1/9 models retain signal: Daikin_RKN gradient = 0.119 (significant). Within Daikin_RKN: young (<30mo) = 6.3%, mid (30-60) = 8.2%, old (60+) = 15.6% (n=9,575). Hot-condition absence check: gradient = 0.082 with n=63,016 (CONDITIONAL_SIGNAL_EXISTS).
          delta: Within Daikin_RKN, 60+ month units have 2.5× the excursion rate of young units (15.6% vs 6.3%). This 9.3pp absolute difference is larger than the entire insulation type spread (0.86pp).
          pattern: 2 (Proxy Absorption) + 5 (Absence)
          verdict: CONTRADICTED
          material: yes (WRONG EXCLUSION)
          executor_note: vehicleRefrigAgeMonths has a SIGNIFICANT, MATERIAL within-model age effect for Daikin_RKN (the highest-excursion reefer model). The 15.6% excursion rate at 60+ months is the highest observed rate for any equipment-level subgroup. Additionally, in hot conditions (>30°C), the overall age gradient is 0.082 (n=63,016), confirming conditional signal. This variable should NOT be excluded. At minimum, it should be an interaction candidate (vehicleRefrigAgeMonths × vehicleRefrigModel) with Daikin_RKN as the modifier. The exclusion decision based on population-level sel_freq masks a severe model-specific degradation effect.
        
        ---
        
        VERIFICATION:
          plan_item: BDT2 — Patterns 2 + 5 (Proxy Absorption + Absence)
          claim: containerAgeMonths absorbed by containerInsulationType; sel_freq 0.0 after saturation
          generator_evidence: Run 3 sel_freq 0.0, avg_rank 7.13; non-monotonic raw data
          alternative_path: verifyProxyAbsorption (age within each insulation type), verifyAbsence within VIP_panel and PIR_foam
          generator_number: sel_freq 0.0
          skeptic_number: Proxy absorption COMPLETE — 0/4 insulation types retain significant gradient. VIP_panel gradient = -0.005 (null, n=154,697). PIR_foam gradient = 0.032 (null, n=95,587).
          delta: All within-type gradients are near zero and non-significant. Safe to exclude.
          pattern: 2 (Proxy Absorption) + 5 (Absence)
          verdict: SUPPORTED
          material: no
          executor_note: Exclusion is validated. containerAgeMonths shows no residual signal within any insulation type.
        
        ---
        
        VERIFICATION:
          plan_item: RE1 — Pattern 10 + 11 (Rare Event Tautology + Sample Size)
          claim: nodePowerStatus=outage: n=256, 79.3% excursion, 12.2× rate ratio
          generator_evidence: Exploratory query; tautology_check: NOT tautological
          alternative_path: verifySampleSizeAdequacy, crossTabulation with nodeRefrigHealthPct<80
          generator_number: 79.3% rate, n=256
          skeptic_number: Sample size check: UNDERPOWERED (detectable effect = 28.1% for outage stratum, though observed 79.3% well exceeds this). Rare event cross-tab: ALL 256 outage shipments have health_below_80. Outage is a STRICT SUBSET of health degradation.
          delta: The 79.3% rate is confirmed but the outage stratum has n=256 with CI width ±5.0pp. More importantly, outage is not independent of health degradation — it's a special case (health=0%).
          pattern: 10 (Rare Event Tautology) + 11 (Sample Size)
          verdict: SUPPORTED (rate confirmed, but not independent of RE2)
          material: no
          executor_note: The 79.3% rate is real but the outage finding should be consolidated with RE2 (health<80%) as a SUBSET rather than listed as a separate finding. nodePowerStatus=outage is the extreme end of nodeRefrigHealthPct=0%, not a separate mechanism.
        
        ---
        
        VERIFICATION:
          plan_item: DAG — Pattern 7 (Collider Conditioning on preDepartureTempC)
          claim: preDepartureTempC is a mediator (safe to saturate for transit-phase analysis), not a collider
          generator_evidence: DAG_EDGES block shows nodeRefrigHealthPct → preDepartureTempC → excursionFlag
          alternative_path: verifyColliderConditioning with treatment=vehicleInsulationRating, confounder=ambientTempAtDispatchC, collider=preDepartureTempC
          generator_number: Unconditional corr(vehicleInsulationRating, ambientTempAtDispatchC) ≈ 0.047
          skeptic_number: Unconditional corr = 0.047, conditional corr (given preDep) = 0.035. No increase. Verdict: NEUTRAL.
          delta: -0.011 (decrease, not increase)
          pattern: 7 (Collider Conditioning)
          verdict: SUPPORTED (safe to saturate)
          material: no
          executor_note: preDepartureTempC is NOT a collider. Conditioning on it does not induce spurious correlations. Safe to saturate for transit-phase analysis.
        
        ---
        
        VERIFICATION:
          plan_item: DD1 — Pattern 8 (Survivorship Bias)
          claim: Survivorship bias operates: 191 vehicles retired (64% of fleet), likely removing worst-performing old units
          generator_evidence: "survivorship bias operates... likely removing the worst-performing old units"
          alternative_path: verifySurvivorshipBias comparing excursion rates of retired vs active vehicles
          generator_number: Not quantified — presented as speculation
          skeptic_number: Retired vehicles: 6.61% excursion rate (n=419,995). Active vehicles: 6.17% (n=143,033). Delta = 0.44pp.
          delta: Retired vehicles only 0.44pp worse than active. No meaningful survivorship bias detected.
          pattern: 8 (Survivorship Bias)
          verdict: CONTRADICTED
          material: no (the speculation was not used to support a hypothesis)
          executor_note: Generator claimed survivorship bias as an explanation for why age effects were weak. The data shows retired vehicles had nearly identical excursion rates to active ones (delta 0.44pp), meaning survivorship bias is NOT a significant factor in this fleet. The weak age effect has other explanations (model confounding, limited within-model age range).
        
        ---
        
        VERIFICATION:
          plan_item: DD2 — Pattern 6 (Deployment Confounding for PIR ranking)
          claim: PIR ranks worst possibly due to deployment to harder conditions or older age distribution
          generator_evidence: Domain discrepancy block citing confounding hypothesis
          alternative_path: deploymentDistribution showing PIR concentration across regions
          generator_number: PIR excursion rate 6.95% (worst)
          skeptic_number: PIR deployment HHI = 0.160 (uniform across regions). PIR in Southeast_GA = 23.3% (vs 13.6% for XPS), suggesting slightly more Southeast-heavy deployment. However, PIR in Southwest_AZ (hottest region) = 15.6% of PIR fleet — lower than XPS (16.1%) and VIP (13.3%). PIR isn't disproportionately deployed to hot regions. Within controlled conditions (25-30°C, low vehicle insulation): PIR 13.3% vs VIP 11.0% — PIR still worse but gap narrows to 2.3pp from marginal 0.86pp.
          delta: PIR's slightly worse performance persists after controlling for ambient and vehicle insulation. This weakens the pure deployment-confounding explanation and suggests some genuine PIR field performance issue.
          pattern: 6 (Ecological Fallacy / Deployment Confounding)
          verdict: SUPPORTED (partial confounding confirmed but genuine PIR underperformance likely)
          material: no
          executor_note: PIR underperformance is real but smaller than marginal numbers suggest. The 2.3pp gap within controlled conditions is more reliable than the 0.86pp marginal gap.
        
        ---
        
        VERIFICATION:
          plan_item: Plan item 13 — Pattern 0 (vehicleCargoVolumeM3 omission)
          claim: vehicleCargoVolumeM3 had sel_freq 0.67 in Run 3 but was not made a hypothesis
          generator_evidence: Run 3 results mention it at rank 3.33
          alternative_path: stratifiedGradient of cargoVolumeM3 on excursionFlag, stratified by containerInsulationType
          generator_number: Not addressed (omitted)
          skeptic_number: Slope varies dramatically by insulation type: VIP_panel = +0.0042 (larger volume → MORE excursion), PIR_foam = -0.0007 (opposite direction). No consistent main effect. Likely an interaction artifact or proxy for vehicle model.
          delta: N/A
          pattern: 0 (Unsubstantiated Claim / Omission)
          verdict: NOT_APPLICABLE (not a missed hypothesis)
          material: no
          executor_note: vehicleCargoVolumeM3's Run 3 signal appears to be an interaction artifact, not a main effect. The generator's decision to omit it was defensible.
        
        ---
        
        VERIFICATION:
          plan_item: H2 — Pattern 12 (Confounder Completeness)
          claim: H2 DAG edges are complete
          generator_evidence: DAG_EDGES block for H2
          alternative_path: verifyConfounderCompleteness with vehicleCargoVolumeM3, containerAgeMonths, vehicleRefrigAgeMonths as candidates
          generator_number: Not quantified
          skeptic_number: No missing confounders detected above 0.1 threshold.
          delta: 0.0
          pattern: 12 (Confounder Completeness)
          verdict: SUPPORTED
          material: no
          executor_note: H2 confounder set appears complete among the tested candidates.
        
        ---
        
        VERIFICATION:
          plan_item: DAG — Pattern 13 (Missing DAG Edges)
          claim: DAG edges across all hypotheses are complete
          generator_evidence: DAG_EDGES blocks in H1, H2, H3
          alternative_path: verifyDAGCompleteness with controls, excluded variables, cross-hypothesis treatments
          generator_number: No missing edges claimed
          skeptic_number: No undocumented associations above 0.1 threshold detected.
          delta: 0.0
          pattern: 13 (DAG Completeness)
          verdict: SUPPORTED
          material: no
          executor_note: DAG appears structurally complete among observed variables.
        
        ---
        
        VERIFICATION:
          plan_item: IC1 — Pattern 4 (Interaction Candidate)
          claim: containerInsulationType × vehicleInsulationRating synergistic interaction
          generator_evidence: Interaction candidate block citing thermal chain reasoning
          alternative_path: crossTabulation of 4 insulation types × 2 vehicle insulation levels
          generator_number: "synergistic" interaction expected
          skeptic_number: Cross-tab shows REVERSED pattern: for ALL 4 container types, high vehicle insulation → HIGHER excursion rate. E.g., VIP + high = 8.11% vs VIP + low = 4.76%. But avg ambient is 0.6°C higher for high-insulation cells, confirming deployment confounding drives the cross-tab pattern.
          delta: Cannot assess interaction due to pervasive deployment confounding in the cross-tab.
          pattern: 4 (Effect Modifier)
          verdict: CONDITIONAL (untestable in raw data due to deployment confounding)
          material: no
          executor_note: The interaction cannot be assessed from raw cross-tabulation because deployment confounding contaminating the vehicleInsulationRating variable. The claimed synergy is plausible on physical grounds but empirically untestable without a more sophisticated identification strategy.
        
        ---
        
        VERIFICATION:
          plan_item: All hypotheses — Pattern 14 (Composed Treatment)
          claim: All treatments are single source columns
          generator_evidence: H1: containerInsulationType, H2: vehicleInsulationRating, H3: nodeRefrigHealthPct
          alternative_path: Schema check
          generator_number: N/A
          skeptic_number: All three are single columns from shipments table
          delta: 0
          pattern: 14 (Composed Treatment)
          verdict: COMPLIANT
          material: no
        
        ---
        
        ## CROSS-CLAIM FINDINGS
        
        CROSS_CLAIM:
          type: RARE_EVENT_OVERLAP
          claims_involved: RE1 (nodePowerStatus=outage), RE2 (nodeRefrigHealthPct<80%)
          finding: Power outage is a STRICT SUBSET of health<80%. All 256 outage shipments have nodeRefrigHealthPct=0%. The health_below_80 group includes all 256 outage cases + 4,155 degraded-but-powered cases. These should be consolidated as a single finding with outage as the extreme tail, not listed as independent rare events.
          material: no (both findings are valid, but independence is overstated)
          executor_note: Recommend merging RE1 into RE2 as a subclass. The prescriptive implication (backup power) is specific to outages, but the analytical finding is one mechanism at two severity levels.
        
        CROSS_CLAIM:
          type: DEPENDENCY
          claims_involved: H2 (vehicleInsulationRating), H1 (containerInsulationType)
          finding: H2's validity depends on resolving the pervasive deployment confounding (well-insulated vehicles → hot regions). The same confounding contaminates the cross-tabulation of H1 × H2 (IC1 interaction candidate). Until the deployment allocation mechanism is properly modeled, H2 effect direction and threshold cannot be reliably separated from deployment patterns. H1's marginal numbers are also affected: VIP's 6.09% rate is inflated by favorable deployment to cool regions.
          material: yes
          executor_note: Both H1 and H2 marginal estimates are contaminated by the same fleet deployment allocation. The stability selection model may control for this via saturation, but the raw-data verification cannot confirm the model's estimates. Recommend explicit deployment allocation modeling (propensity score or matching) before finalizing either hypothesis.
        
        CROSS_CLAIM:
          type: AGGREGATE_INCONSISTENCY
          claims_involved: H1, H2, H3
          finding: H1 claims 0.86pp effect (6.95% - 6.09%), H2 claims a threshold effect (unstated magnitude), H3 claims 37.7pp effect for health<80% (but affects only 0.78% of shipments). The base rate is 6.5%. H3's absolute excess is 37.7pp × 4,411 = ~1,660 excess excursions out of 36,589 total excursions = 4.5% attributable. H1's effect, if causal, would be 0.86pp × 96K PIR shipments = ~825 attributable excursions = 2.3%. These are compatible and well below 100%, so no aggregate inconsistency.
          material: no
          executor_note: No aggregate inconsistency.
        
        ---
        
        ## COVERAGE SUMMARY
        
        | Plan Item | Status | Verdict | Material? |
        |-----------|--------|---------|-----------|
        | 1. H1 direction (pattern 3) | TESTED | CONDITIONAL | YES |
        | 2. H2 threshold (patterns 3, threshold) | TESTED | OVERSTATED | YES |
        | 3. H3 mediation (pattern 1) | TESTED | INCOMPLETE | YES |
        | 4. BDT1 vehicleRefrigAgeMonths (patterns 2,5) | TESTED | CONTRADICTED (wrong exclusion) | YES |
        | 5. BDT2 containerAgeMonths (patterns 2,5) | TESTED | SUPPORTED | NO |
        | 6. RE1 sample size (pattern 11) | TESTED | UNDERPOWERED but confirmed | NO |
        | 7. RE1×RE2 independence (C3) | TESTED | STRICT SUBSET (consolidate) | NO |
        | 8. Collider check on preDepartureTempC (pattern 7) | TESTED | SAFE | NO |
        | 9. Survivorship bias (pattern 8) | TESTED | CONTRADICTED (not confirmed) | NO |
        | 10. PIR deployment confounding (pattern 6) | TESTED | SUPPORTED (partial) | NO |
        | 11. H2 confounder completeness (pattern 12) | TESTED | COMPLETE | NO |
        | 12. H2 ecological fallacy (pattern 6) | TESTED | SUPPORTED (but positive direction!) | YES |
        | 13. vehicleCargoVolumeM3 omission (pattern 0) | TESTED | NOT_APPLICABLE | NO |
        | 14. Composed treatment (pattern 14) | TESTED | COMPLIANT | NO |
        | 15. IC1 interaction (pattern 4) | TESTED | CONDITIONAL (untestable) | NO |
        | 16. DAG completeness (pattern 13) | TESTED | DAG_COMPLETE | NO |
        """;

    String REBUTE = """
        ## Finding-by-Finding Responses
        
        ---
        
        ### Finding 1: H1 containerInsulationType direction reverses in hot conditions
        
        **NARROW**
        
        The skeptic's finding reveals a legitimate scope limitation on the marginal numbers I cited, but crucially, the skeptic's own most controlled analysis **confirms** the VIP advantage:
        
        - When controlling for both ambient temperature (25–30°C) AND vehicle insulation (low), VIP shows 11.0% vs PUR 14.2% — a 3.2pp VIP advantage, **larger** than the marginal 0.86pp I reported.
        - The reversal in uncontrolled hot conditions (VIP 32.9% worst) reflects residual within-temperature-band confounding (vehicle quality, route difficulty) that my stability selection controlled for simultaneously across 10+ variables.
        - My Run 3 (sel_freq 1.0) saturated ambient temperature, vehicle insulation rating, refrigeration model, and node-level variables. The skeptic's stratification by temperature alone controls for 1 variable.
        
        The legitimate scope issue: the **marginal numbers** I cited (6.09% vs 6.95%) are confounded by VIP's overdeployment to PacificNW_OR (21.8% of VIP fleet vs 5.6% PIR). These marginal rates do not represent causal effects and should not have been cited as the primary evidence.
        
        **New scope boundary**: The containerInsulationType ranking (VIP advantage) is regime-dependent and holds within strata that control for deployment allocation. In mild conditions (<20°C), all types perform near-identically. The within-stratum VIP advantage (~2–3pp) concentrates in moderate-to-hot conditions where thermal differentiation is thermodynamically meaningful.
        
        ---
        
        ### Finding 2: H2 vehicleInsulationRating threshold (0.762) and direction (-1)
        
        **HOLD**
        
        The skeptic's alternative analyses control for strictly fewer confounders than my original identification strategy:
        
        **(a)** The unconditional threshold analysis (inflection at 0.908) controls for **zero** variables. My SHAP result came from Run 2a, which simultaneously saturated ambient temperature, pre-departure temperature, container insulation type, node refrigeration health, and hub-level variables.
        
        **(b)** The ecological fallacy check (within-region positive slope of +0.144) controls for **1 variable** (region). Within-region deployment confounding persists: within Southwest_AZ, well-insulated vehicles may serve the most extreme nodes/routes. My SS controlled for multiple node-level and operational variables beyond region.
        
        **(c)** The cross-tabulation showing positive direction for all container types is bivariate. The skeptic's own analysis confirmed 0.6–2.0°C higher ambient exposure in the high-insulation vehicle cells — this IS the deployment confounding my SS was designed to remove.
        
        **(d)** The pre-departure-controlled analysis (5–7°C) restricts excursion rates to 0.1–1.0%, eliminating virtually all outcome variance. This is underpowered by construction.
        
        The SHAP breakpoint at 0.762 has **49/50 convergence** with IQR 0.758–0.767. This level of consensus across bootstrap samples is very strong model-based evidence. The raw positive direction is the **expected** pattern under deployment confounding: well-insulated vehicles are deployed to hotter regions. Only multivariate control can isolate the transit-phase insulation effect from this allocation pattern.
        
        The original claim stands unchanged. The execution stage with proper propensity-weighted or matched estimation will adjudicate whether the multivariate model's direction is correct.
        
        ---
        
        ### Finding 3: H3 nodeRefrigHealthPct mediation — partial not full
        
        **NARROW**
        
        The skeptic's bivariate partial correlation (controlling for 1 variable: preDepartureTempC) legitimately identifies that preDepartureTempC alone does not fully absorb the health signal. The residual partial correlation of −0.083 (44% reduction from −0.149) could represent:
        
        1. A genuine direct path (degraded refrigeration → temperature instability during loading, impaired cooling recovery after door openings) — mechanisms beyond the single pre-departure temperature reading.
        2. Confounding absorbed by other variables in my multivariate model (ambient temperature, hub quality) that the bivariate test doesn't control for.
        
        My Run 3 sel_freq of 0.04 reflects the multivariate residual after controlling for 10+ variables, not just preDepartureTempC. The distinction matters: claiming "fully mediated through preDepartureTempC" requires that removing only preDep eliminates the signal, which the skeptic shows it does not.
        
        **New scope boundary**: Revised from "fully mediated" to "predominantly mediated through preDepartureTempC." The DAG should include both a mediated path (nodeRefrigHealthPct → preDepartureTempC → excursionFlag) and a possible direct path (nodeRefrigHealthPct → excursionFlag). The sel_freq of 0.04 in Run 3 indicates the combined multivariate controls nearly eliminate the health signal, but the exact decomposition between mediation through preDep and absorption by other controls cannot be determined from SS alone.
        
        ---
        
        ### Finding 4: BDT1 vehicleRefrigAgeMonths — Daikin_RKN retains signal
        
        **NARROW**
        
        The population-level sel_freq of 0.0 is accurate — vehicleRefrigAgeMonths does not stably predict excursions across the full population. My original BDT1 block **already anticipated** model-specific conditional effects and explicitly recommended: *"GRF_heterogeneity analysis with proposed_modifiers: vehicleRefrigModel, ambientTempAtDispatchC."* The Daikin_RKN finding (15.6% at 60+ months vs 6.3% at <30 months, 2.5× ratio) is exactly the type of conditional effect that block was designed to surface.
        
        The skeptic's finding validates my recommended downstream analysis rather than contradicting my population-level result. Stability selection is designed for population-level stable predictors; model-specific subgroup effects require heterogeneity analysis (GRF), which is why I flagged it.
        
        **New scope boundary**: The absorption claim holds for 8/9 refrigeration models. Daikin_RKN retains a material within-model age gradient (0.119) with excursion rates ranging from 6.3% (young) to 15.6% (old). Updated recommendation: prioritize Daikin_RKN subgroup in downstream GRF analysis. Add new interaction candidate: vehicleRefrigAgeMonths × vehicleRefrigModel.
        
        ---
        
        ### Finding 5: BDT2 containerAgeMonths — SUPPORTED
        **HOLD** — Skeptic confirms complete proxy absorption across all 4 insulation types. No change needed.
        
        ### Finding 6: RE1 sample size — SUPPORTED
        **HOLD** — The n=256 stratum is technically underpowered for small effects, but the observed effect (79.3% vs 6.5% baseline) is so massive it's unambiguously detectable. The power analysis limitation is a mathematical property of small n, not a substantive concern given the 12.2× rate ratio.
        
        ### Finding 7: RE1 × RE2 overlap — STRICT SUBSET
        **NARROW** — The skeptic correctly identifies that all 256 outage cases have nodeRefrigHealthPct = 0%, making outage a strict subset of health<80%. Updated: RE1 and RE2 represent the same mechanism at different severity levels. RE1 (outage) is the extreme tail of RE2 (degraded health). Prescriptive implications remain distinct (backup power vs. maintenance scheduling), but these are no longer independent analytical findings.
        
        ### Finding 8: Collider check on preDepartureTempC — SUPPORTED
        **HOLD** — Conditioning on preDepartureTempC decreases the correlation (0.047 → 0.035), confirming it is NOT a collider. Safe to saturate for transit-phase analysis. No change needed.
        
        ### Finding 9: Survivorship bias — CONTRADICTED
        **ACCEPT** — The skeptic's finding (retired vehicles 6.61% vs active 6.17%, delta 0.44pp) directly contradicts my speculation that survivorship bias explains weak age signals. With 191 retired vehicles showing nearly identical excursion rates to active ones, survivorship is not a meaningful factor in this fleet. DD1's absence hypothesis should be revised to remove survivorship bias as an explanation.
        
        ### Finding 10: PIR deployment confounding — SUPPORTED
        **HOLD** — Confirmed. PIR underperformance is real but smaller than marginal numbers suggest. Within controlled conditions the gap narrows to ~2.3pp, consistent with my domain discrepancy framing.
        
        ### Finding 11: H2 confounder completeness — SUPPORTED
        **HOLD** — No missing confounders detected above threshold. No change needed.
        
        ### Finding 12: H2 ecological fallacy — positive direction within regions
        **HOLD** — Same underlying issue as Finding 2. The within-region positive slope (+0.144) controls for 1 variable (region). Within-region deployment confounding persists and my SS controlled for 10+ variables simultaneously. See Finding 2 response.
        
        ### Finding 13: vehicleCargoVolumeM3 omission — NOT_APPLICABLE
        **HOLD** — Skeptic confirms the omission was defensible. Stratified gradient shows inconsistent slope direction across insulation types (positive for VIP/XPS, negative for PIR/PUR), confirming it's an interaction artifact rather than a missed main effect.
        
        ### Finding 14: Composed treatment — COMPLIANT
        **HOLD** — All three treatments are single source columns from the shipments table. No change needed.
        
        ### Finding 15: IC1 containerInsulationType × vehicleInsulationRating — untestable
        **HOLD** — The skeptic acknowledges the interaction cannot be assessed from raw cross-tabulation due to pervasive deployment confounding. This is consistent with my original framing as a candidate for downstream investigation with proper causal methods.
        
        ### Finding 16: DAG completeness — COMPLETE
        **HOLD** — No undocumented associations above threshold. No change needed.
        
        ---
        
        ### Cross-Claims
        
        **RE overlap**: NARROW — Already addressed in Finding 7. Consolidate RE1 into RE2 as the extreme tail of one mechanism.
        
        **H1/H2 deployment dependency**: HOLD — My SS already controlled for deployment confounding via saturation of ambient temperature and climate variables. The skeptic's bivariate analyses cannot reproduce this multivariate control, which is expected, not evidence against the hypotheses. The execution stage should use propensity-weighted estimation to confirm.
        
        **Aggregate inconsistency**: HOLD — Skeptic confirms no aggregate inconsistency. Attributable fractions are compatible and well below 100%.
        
        ---
        
        ## COMPLETE REVISED STRUCTURED OUTPUT
        
        ---
        
        ```
        HYPOTHESIS: H1
          treatment: containerInsulationType
          treatment_scope: containers (reachable enrichment from shipments via containerId)
          treatment_form: categorical
          threshold_value: N/A
          threshold_convergence: N/A
          outcome: excursionFlag
          expected_direction: VIP_panel < PUR_foam < XPS_foam < PIR_foam (excursion rate)
          effect_scope: [REVISED] Regime-dependent. VIP advantage concentrates in moderate-
            to-hot conditions (ambient >20°C) where thermal differentiation is\s
            thermodynamically meaningful. In mild conditions (<20°C), all types perform\s
            near-identically (~0.1-0.2% excursion). Marginal rates (VIP 6.09%, PIR 6.95%)\s
            are confounded by deployment allocation and do NOT represent causal effects.
            Within-stratum VIP advantage (~2-3pp) is larger than marginal spread (0.86pp).
          effect_modifiers: [ambientTempAtDispatchC, vehicleInsulationRating]
          independently_actionable_mediators: []
        
          ANCHOR_GROUNDING:
            anchor_entity: equipment thermal chain
            reachable_enrichment: [containers, vehicles, hubs/nodes]
            iteration_chain:
              - run_1: {target: excursionFlag, saturated: [], top_feature: preDepartureTempC,
                        classification: A (near-outcome mediator),
                        mechanism: pre-departure thermal state,
                        action: saturate + flip target}
              - run_2a: {target: excursionFlag,
                         saturated: [preDepartureTempC, ambientTempAtDispatchC,\s
                                    hubId.climateZone, hubId.region],
                         top_feature: containerInsulationType,
                         classification: D (anchor-internal actionable),
                         mechanism: container thermal barrier,
                         action: proceed to SHAP + continue saturation}
              - run_2b: {target: preDepartureTempC (FLIP),
                         saturated: [ambientTempAtDispatchC, hubId.climateZone, hubId.region],
                         top_feature: nodeRefrigHealthPct,
                         classification: D (anchor-internal actionable),
                         mechanism: node refrigeration performance,
                         action: feeds H3}
              - run_3: {target: excursionFlag,
                        saturated: [preDepartureTempC, ambientTempAtDispatchC,
                                   hubId.climateZone, hubId.region, vehicleRefrigModel],
                        top_feature: containerInsulationType (sel_freq 1.0),
                        classification: D (anchor-internal actionable),
                        mechanism: container thermal barrier,
                        action: confirmed treatment, SHAP analysis}
            saturated_mechanisms:
              - mechanism: geographic/climate
                features_saturated: [ambientTempAtDispatchC, hubId.region, hubId.climateZone]
                reason: exogenous — cannot control weather or geography
              - mechanism: pre-departure thermal state
                features_saturated: [preDepartureTempC]
                reason: mediator — saturated for transit-phase analysis
              - mechanism: equipment cohort proxy
                features_saturated: [vehicleRefrigModel]
                reason: proxy — categorical bundles age, specs, deployment region
        
          DAG_EDGES:
            - (containerInsulationType, excursionFlag) — thermal barrier quality determines
              heat ingress rate during transit
            - (ambientTempAtDispatchC, excursionFlag) — higher ambient increases heat load
            - (ambientTempAtDispatchC, containerInsulationType) — deployment allocation:
              different container types deployed to different climate regions
            - (hubId.region, containerInsulationType) — fleet allocation varies by region
            - (hubId.region, ambientTempAtDispatchC) — geography determines climate
            - (preDepartureTempC, excursionFlag) — starting temperature affects transit
              outcome; saturated to isolate transit-phase insulation effect
        
          CONFOUNDER_REASONING:
            ambientTempAtDispatchC: Causes both (a) heat load during transit (→ excursion)
              and (b) deployment decisions about which container types to stock at which
              hubs (→ containerInsulationType observed distribution). True confounder —
              upstream of both treatment and outcome.
            hubId.region: Geographic assignment determines climate exposure AND fleet
              composition decisions. VIP containers overrepresented in PacificNW (21.8%)
              vs PIR (5.6%). Upstream confounder for both treatment and outcome.
        
          ENRICHMENT_JOINS:
            - main_table_key: containerId
              join_table: containers
              join_key: containerId
              value_column: containerInsulationType
              default_value: null (inner join — every shipment has container)
        
          DERIVED_FEATURES: none
        
          UNMEASURED_VARIABLES:
            - name: containerThermalConductivity
              intended_computation: continuous measure of insulation thermal performance
              failure_reason: not recorded — only categorical type available
              impact: the categorical type may bundle multiple thermal properties;
                continuous conductivity would provide finer-grained treatment variable
        
          EVIDENCE:
            stability_score: 1.0 (Run 3 consensus — highest possible)
            shap_curve_form: categorical — VIP_panel lowest SHAP, PIR_foam highest
            breakpoint: N/A (categorical)
            domain_alignment: [REVISED] Partially aligned. Domain predicts VIP superior
              thermal performance (lower conductivity). Data confirms VIP advantage
              within controlled strata (~2-3pp). However, the marginal ranking is
              confounded by favorable VIP deployment to cooler regions. Domain did NOT
              predict the deployment-driven reversal in hot uncontrolled conditions.
            domain_discrepancy: [REVISED] Marginal numbers overstate VIP advantage
              in mild conditions and mask deployment confounding. Within Southwest_AZ
              (hottest region), VIP shows HIGHEST excursion rate (17.1%) before
              controlling for vehicle insulation and route-level confounders. Effect
              is real but regime-dependent and requires multivariate control to isolate.
        
          DOMAIN_RANKING:
            ranking: [PIR_foam, XPS_foam, PUR_foam, VIP_panel] (worst to best excursion)
            expected_ratio: within-stratum spread ~2-3pp in moderate-hot conditions
            source: thermal conductivity ranking of insulation materials
        
          MEASUREMENT_METADATA: []
        ```
        
        ```
        HYPOTHESIS: H2
          treatment: vehicleInsulationRating
          treatment_scope: vehicles (reachable enrichment from shipments via vehicleId)
          treatment_form: binary_threshold
          threshold_value: 0.762
          threshold_convergence: 49/50 converged, IQR 0.758-0.767
          outcome: excursionFlag
          expected_direction: -1 (higher insulation rating → lower excursion probability)
          effect_modifiers: [ambientTempAtDispatchC]
          independently_actionable_mediators: []
        
          ANCHOR_GROUNDING:
            anchor_entity: equipment thermal chain
            reachable_enrichment: [containers, vehicles, hubs/nodes]
            iteration_chain:
              - run_2a: {target: excursionFlag,
                         saturated: [preDepartureTempC, ambientTempAtDispatchC,
                                    hubId.climateZone, hubId.region],
                         top_feature: vehicleInsulationRating (sel_freq 0.71),
                         classification: D (anchor-internal actionable),
                         mechanism: vehicle thermal barrier,
                         action: SHAP analysis}
              - run_3: {target: excursionFlag,
                        saturated: [preDepartureTempC, ambientTempAtDispatchC,
                                   hubId.climateZone, hubId.region, vehicleRefrigModel],
                        top_feature: vehicleInsulationRating (sel_freq 0.71, rank 2),
                        classification: D (confirmed),
                        mechanism: vehicle thermal barrier,
                        action: confirmed treatment}
            saturated_mechanisms:
              - mechanism: geographic/climate
                features_saturated: [ambientTempAtDispatchC, hubId.region, hubId.climateZone]
                reason: exogenous — pervasive deployment confounding (well-insulated vehicles
                  deployed to hotter regions, causing positive raw correlation that masks\s
                  true negative causal effect)
              - mechanism: pre-departure thermal state
                features_saturated: [preDepartureTempC]
                reason: mediator — saturated for transit-phase insulation analysis
              - mechanism: equipment cohort proxy
                features_saturated: [vehicleRefrigModel]
                reason: proxy for age, specs, deployment vintage
        
          DAG_EDGES:
            - (vehicleInsulationRating, excursionFlag) — higher vehicle insulation reduces
              heat ingress during transit, lowering excursion probability
            - (ambientTempAtDispatchC, excursionFlag) — higher ambient increases heat load
            - (ambientTempAtDispatchC, vehicleInsulationRating) — deployment allocation:
              well-insulated vehicles systematically assigned to hotter regions, creating
              POSITIVE raw correlation that masks the true NEGATIVE causal effect
            - (vehicleRefrigModel, vehicleInsulationRating) — model determines insulation
              specs
            - (hubId.region, ambientTempAtDispatchC) — geography determines climate
        
          CONFOUNDER_REASONING:
            ambientTempAtDispatchC: Strong confounder. Well-insulated vehicles are deployed
              to hotter regions (skeptic confirmed 0.6-2.0°C higher ambient in high-
              insulation cells). This creates a positive marginal association (higher
              insulation → higher excursion) that is the REVERSE of the causal direction.
              Only multivariate control (10+ variables in SS) can isolate the true
              transit-phase effect. Bivariate and within-region analyses (controlling for
              1 variable) cannot remove within-region deployment confounding.
            vehicleRefrigModel: Determines insulation rating specifications and bundles
              age, specs, and deployment patterns. Saturated to unbundle continuous
              insulation effect from categorical model effects.
        
          ENRICHMENT_JOINS:
            - main_table_key: vehicleId
              join_table: vehicles
              join_key: vehicleId
              value_column: vehicleInsulationRating
              default_value: null (inner join)
        
          DERIVED_FEATURES: none
        
          UNMEASURED_VARIABLES: none
        
          EVIDENCE:
            stability_score: 0.71 (Run 3 consensus)
            shap_curve_form: threshold — sharp negative slope change at 0.762
            breakpoint: 0.762 (49/50 converged, IQR 0.758-0.767)
            domain_alignment: Domain predicts higher insulation should reduce excursion
              risk. SHAP curve confirms negative direction AFTER multivariate control.
            domain_discrepancy: Raw data shows POSITIVE direction at every level of
              bivariate stratification tested (unconditional, within-region, within
              container type, within hot conditions). This positive raw correlation is
              consistent with the documented deployment confounding mechanism. The
              multivariate SS and SHAP results show negative direction only after
              simultaneous control for 10+ variables. Execution stage should confirm
              via propensity-weighted estimation.
            NOTE_FOR_EXECUTION: The raw-data direction (positive) and SS direction\s
              (negative) diverge more than typical. This hypothesis requires careful\s
              identification strategy in estimation. The 0.762 threshold should be\s
              treated as a model-derived estimate requiring confirmation, not a\s
              raw-data inflection point.
        
          MEASUREMENT_METADATA: []
        ```
        
        ```
        HYPOTHESIS: H3 [REVISED]
          treatment: nodeRefrigHealthPct
          treatment_scope: hubs/nodes (reachable enrichment from shipments via hubId)
          treatment_form: binary_threshold
          threshold_value: 80
          threshold_convergence: [from Run 2b SHAP]
          outcome: excursionFlag
          expected_direction: -1 (higher health → lower excursion probability)
          mediation_status: [REVISED] PREDOMINANTLY MEDIATED through preDepartureTempC,
            with a possible direct path. Bivariate partial correlation retains -0.083
            after partialling out preDepartureTempC (44% reduction from -0.149).\s
            Multivariate sel_freq drops to 0.04 when preDep + other controls are\s
            saturated, suggesting additional confounders absorb most residual signal.
            Exact decomposition between mediation and confounding cannot be determined\s
            from SS alone.
          effect_modifiers: [ambientTempAtDispatchC]
          independently_actionable_mediators: [preDepartureTempC]
        
          ANCHOR_GROUNDING:
            anchor_entity: equipment thermal chain
            reachable_enrichment: [containers, vehicles, hubs/nodes]
            iteration_chain:
              - run_2b: {target: preDepartureTempC (FLIP),
                         saturated: [ambientTempAtDispatchC, hubId.climateZone, hubId.region],
                         top_feature: nodeRefrigHealthPct (sel_freq 0.67),
                         classification: D (anchor-internal actionable),
                         mechanism: node refrigeration performance,
                         action: SHAP analysis for preDep prediction}
              - run_3: {target: excursionFlag,
                        saturated: [preDepartureTempC, ambientTempAtDispatchC,
                                   hubId.climateZone, hubId.region, vehicleRefrigModel],
                        top_feature: nodeRefrigHealthPct (sel_freq 0.04),
                        classification: D (signal predominantly absorbed by preDep saturation),
                        mechanism: node refrigeration performance,
                        action: confirms mediation path but does not confirm full mediation}
            saturated_mechanisms:
              - mechanism: geographic/climate
                features_saturated: [ambientTempAtDispatchC, hubId.region, hubId.climateZone]
                reason: exogenous
              - mechanism: pre-departure thermal state
                features_saturated: [preDepartureTempC]
                reason: mediator on this treatment's path — saturated deliberately to test
                  residual direct effect. Saturation is methodologically appropriate here
                  because we want to know whether health matters BEYOND its effect on
                  pre-departure temperature.
        
          DAG_EDGES:
            - (nodeRefrigHealthPct, preDepartureTempC) — degraded refrigeration fails to
              achieve target pre-cooling temperature
            - (preDepartureTempC, excursionFlag) — higher starting temperature increases
              excursion probability during transit
            - (nodeRefrigHealthPct, excursionFlag) — [REVISED] possible DIRECT path through
              mechanisms beyond pre-departure temperature: temperature instability during
              loading, impaired cooling recovery after door openings, quality of pre-cooling
              process not captured by single temperature reading
            - (ambientTempAtDispatchC, preDepartureTempC) — ambient heat load makes
              pre-cooling harder
            - (ambientTempAtDispatchC, nodeRefrigHealthPct) — extreme ambient conditions
              may stress refrigeration equipment, lowering health scores
        
          CONFOUNDER_REASONING:
            ambientTempAtDispatchC: Upstream of both treatment (extreme heat stresses
              refrigeration equipment) and outcome (via pre-departure temp and transit
              heat load). True confounder.
        
          ENRICHMENT_JOINS:
            - main_table_key: hubId
              join_table: hubs
              join_key: hubId
              value_column: nodeRefrigHealthPct
              default_value: null
        
          DERIVED_FEATURES: none
        
          UNMEASURED_VARIABLES: none
        
          EVIDENCE:
            stability_score: 0.67 (Run 2b predicting preDepartureTempC);
                             0.04 (Run 3 predicting excursionFlag with preDep saturated)
            shap_curve_form: threshold at 80% — steep change in SHAP below this level
            breakpoint: 80 (from Run 2b SHAP)
            domain_alignment: Domain confirms refrigeration health determines pre-cooling
              effectiveness. Below 80% health, systems cannot reliably reach target
              temperatures.
            domain_discrepancy: [REVISED] "Fully mediated" claim overstated. Bivariate
              partial correlation retains -0.083 (44% of original signal) after controlling
              for preDepartureTempC alone. The multivariate sel_freq of 0.04 reflects
              combined absorption by preDep AND other controls, not mediation through
              preDep alone. A genuine direct path likely exists through temperature
              instability mechanisms not captured by the single pre-departure reading.
        
          MEASUREMENT_METADATA: []
        ```
        
        ---
        
        ```
        BELOW_DETECTION_THRESHOLD: BDT1 [REVISED]
          feature: vehicleRefrigAgeMonths
          anchor_entity: vehicles
          ss_rank_after_saturation: 5.29 (Run 3)
          selection_frequency: 0.0 (Run 3)
          domain_expectation: Older refrigeration units should show degraded performance
            and higher excursion rates through compressor wear, refrigerant loss, and
            insulation degradation.
          absence_hypothesis: [REVISED] screening_by_proxy (PARTIAL — 8/9 models) +
            conditional_effect (Daikin_RKN)
          absence_reasoning: [REVISED] vehicleRefrigModel categorical absorbs the age
            signal for 8 of 9 models. However, Daikin_RKN retains a material within-model
            age gradient of 0.119 with excursion rates of 6.3% (young <30mo), 8.2% (mid
            30-60mo), and 15.6% (old 60+mo) — a 2.5× ratio representing a 9.3pp absolute
            difference that exceeds the entire insulation type marginal spread.
            Additionally, in hot conditions (>30°C), a population-level gradient of 0.082
            emerges across 63K observations, confirming temperature-conditional signal.
            Survivorship bias was investigated and RULED OUT — retired vehicles show only
            0.44pp worse excursion rate than active vehicles.
          recommended_downstream:
            analysis_type: GRF_heterogeneity
            proposed_modifiers: [vehicleRefrigModel, ambientTempAtDispatchC]
            rationale: [REVISED] Prioritize Daikin_RKN subgroup analysis. The 2.5× age
              ratio within this single model is the largest equipment-level effect found
              in the entire analysis. GRF with vehicleRefrigModel as modifier should
              surface the conditional treatment effect that population-level SS cannot
              detect. Hot-condition stratification provides a secondary validation path.
        ```
        
        ```
        BELOW_DETECTION_THRESHOLD: BDT2
          feature: containerAgeMonths
          anchor_entity: containers
          ss_rank_after_saturation: 7.13 (Run 3)
          selection_frequency: 0.0 (Run 3)
          domain_expectation: Older containers should show insulation degradation and
            higher excursion rates.
          absence_hypothesis: screening_by_proxy (COMPLETE)
          absence_reasoning: containerInsulationType fully absorbs the age signal. Within
            all 4 insulation types, age gradients are near zero and non-significant
            (VIP: -0.005, PIR: 0.032, XPS and PUR similarly null). The categorical
            type variable captures all between-container thermal variation that matters
            for excursion risk.
          recommended_downstream:
            analysis_type: GRF_heterogeneity
            proposed_modifiers: [containerInsulationType, ambientTempAtDispatchC]
            rationale: Proxy absorption is complete per skeptic verification. Low
              priority for further analysis. If needed, GRF can confirm null within-type
              age effect.
        ```
        
        ---
        
        ```
        RARE_EVENT_FINDING: RE1 [REVISED]
          feature: nodePowerStatus
          rare_level: outage
          n: 256
          pct_of_total: 0.05%
          outcome_rate_at_rare: 79.3%
          outcome_rate_baseline: 6.5%
          rate_ratio: 12.2×
          anchor_internal: yes (node entity, reachable via hubId)
          mechanism: Power outage disables refrigeration entirely, causing rapid
            temperature rise above safe thresholds.
          tautology_check: NOT tautological — outage is a cause of excursion, not
            definitionally equivalent. Not all outage shipments excurse (20.7% do not).
          subset_relationship: [REVISED] STRICT SUBSET of RE2 (health<80%). All 256
            outage shipments have nodeRefrigHealthPct = 0%. Outage represents the
            extreme tail of the health degradation mechanism (RE2), not an independent
            finding. The 79.3% excursion rate for outages vs 42.0% for non-outage
            degraded health (n=4,155) shows severity-graded response within one
            mechanism.
          conditional_context:
            - condition: nodeRefrigHealthPct = 0% (outage) vs nodeRefrigHealthPct 1-79%
                         (degraded but powered)
              finding: Outage (79.3%) vs degraded-but-powered (42.0%) — the presence
                of ANY refrigeration, even degraded, nearly halves excursion risk.
          prescriptive_implication: Backup power systems or rapid-response protocols for
            outage detection at dispatch nodes. Prevention of total power loss is more
            impactful than incremental health improvement.
          downstream_action: VERIFY_AND_PRESCRIBE
        ```
        
        ```
        RARE_EVENT_FINDING: RE2 [REVISED]
          feature: nodeRefrigHealthPct
          rare_level: below 80%
          n: 4,411 (includes 256 outage cases)
          pct_of_total: 0.78%
          outcome_rate_at_rare: 44.2%
          outcome_rate_baseline: 6.5%
          rate_ratio: 6.8×
          anchor_internal: yes (node entity)
          mechanism: Degraded refrigeration cannot achieve or maintain target pre-cooling
            temperatures. Shipments depart warm and remain above threshold during transit.
          tautology_check: NOT tautological
          superset_relationship: [REVISED] Includes all RE1 outage cases. The health<80%
            finding represents a SEVERITY GRADIENT: outage (health=0%, 79.3% excursion) →
            degraded (health 1-79%, 42.0%) → normal (health ≥80%, 6.2%). This is one
            continuous mechanism, not two independent rare events.
          conditional_context:
            - condition: Severity gradient within health<80%
              finding: Outage=79.3%, degraded-but-powered=42.0%, normal=6.2%. Each
                severity level shows distinct excursion risk.
          prescriptive_implication: Preventive maintenance scheduling to keep node
            refrigeration above 80% health. Prioritize nodes showing degradation trend
            before they reach critical threshold.
          downstream_action: VERIFY_AND_PRESCRIBE
        ```
        
        ---
        
        ```
        DOMAIN_DISCREPANCY: DD1 [REVISED — ACCEPTED survivorship component]
          expected_driver: Equipment aging (both vehicle refrigeration and container)
            should predict excursion risk through mechanical degradation.
          domain_evidence_strength: WELL_ESTABLISHED
          empirical_finding: Population-level sel_freq 0.0 for both age variables after
            vehicleRefrigModel and containerInsulationType are saturated.
          ss_rank: vehicleRefrigAgeMonths 5.29 (Run 3), containerAgeMonths 7.13 (Run 3)
          absence_hypothesis: screening_by_proxy + conditional_effect
          reasoning: [REVISED] Equipment categorical variables (vehicleRefrigModel,
            containerInsulationType) absorb the age signal by bundling age cohorts with
            specification tiers and deployment patterns. The absorption is COMPLETE for
            containerAgeMonths (0/4 types retain signal) but INCOMPLETE for
            vehicleRefrigAgeMonths (Daikin_RKN retains 0.119 gradient, 2.5× age ratio).
            Survivorship bias was tested and RULED OUT: retired vehicles show only 0.44pp
            worse excursion rate than active (6.61% vs 6.17%), indicating the fleet does
            not systematically remove worst performers in a way that masks age effects.
          proposed_resolution:
            method: GRF heterogeneity analysis with vehicleRefrigModel as treatment
              modifier for vehicleRefrigAgeMonths
            expected_result_if_real: Daikin_RKN shows strong positive CATE for age;
              other models show null or weak CATE
            expected_result_if_spurious: All models including Daikin_RKN show null CATE
              after proper multivariate control
        ```
        
        ```
        DOMAIN_DISCREPANCY: DD2
          expected_driver: PIR foam insulation should perform comparably to or better than
            XPS/PUR based on thermal conductivity specifications.
          domain_evidence_strength: DOCUMENTED
          empirical_finding: PIR ranks last in excursion rate (6.95% marginal). Within
            controlled conditions (25-30°C, low vehicle insulation), PIR shows 13.3%
            vs VIP 11.0% — gap narrows but PIR still worst.
          ss_rank: containerInsulationType rank 1 (Run 3, sel_freq 1.0)
          absence_hypothesis: confounding + measurement_resolution
          reasoning: PIR deployment is relatively uniform across regions (HHI 0.160),
            not disproportionately deployed to hot regions. The within-stratum PIR
            underperformance (~2pp vs VIP) may reflect real field performance issues
            (moisture sensitivity, installation quality) or unmeasured confounders
            (container age distribution within PIR fleet, PIR-specific route assignments).
          proposed_resolution:
            method: Within-PIR analysis controlling for container age, hub assignment,
              and vehicle pairing to isolate PIR-specific field degradation
            expected_result_if_real: PIR underperformance persists after all controls,
              suggesting material-specific field degradation
            expected_result_if_spurious: PIR performance equalizes with XPS/PUR after
              controlling for confounders, leaving only VIP as differentiated
        ```
        
        ---
        
        ```
        INTERACTION_CANDIDATE: IC1
          components: [containerInsulationType, vehicleInsulationRating]
          component_hypotheses: [H1, H2]
          joint_signal_evidence: Physical thermal chain reasoning — container and vehicle
            insulation operate in series as thermal barriers. SHAP interactions from
            Run 2a flagged vehicleInsulationRating as effect modifier for
            containerInsulationType. The combined thermal resistance determines total
            heat ingress.
          expected_interaction_form: synergistic
          reasoning: Total thermal resistance is additive (R_container + R_vehicle).
            Improving one barrier has diminishing returns if the other is the binding
            constraint. Conversely, upgrading both simultaneously has super-additive
            benefit.
          verification_note: [REVISED] Skeptic's cross-tabulation showed deployment
            confounding prevents raw-data interaction assessment — high vehicle
            insulation cells have 0.6-2.0°C higher ambient exposure. Interaction must
            be assessed after propensity weighting or matching to remove deployment
            allocation.
        ```
        
        ```
        INTERACTION_CANDIDATE: IC2
          components: [containerInsulationType, ambientTempAtDispatchC]
          component_hypotheses: [H1]
          joint_signal_evidence: [REVISED] Skeptic's stratified analysis directly confirms
            regime dependence: in mild conditions (<20°C), all insulation types show
            ~0.1-0.2% excursion (no differentiation). In hot conditions (>30°C), spread
            widens to ~4.7pp. In controlled moderate-hot conditions (25-30°C), VIP
            advantage is ~2-3pp.
          expected_interaction_form: regime_change
          reasoning: Below a thermal stress threshold (~20°C ambient), all container
            types provide sufficient insulation and excursions are driven by non-thermal
            mechanisms. Above this threshold, insulation quality becomes the binding
            constraint and type differentiation emerges.
          verification_note: [REVISED] Skeptic's analysis shows the direction ranking
            within hot conditions (VIP worst in raw data) reverses when vehicle
            insulation is also controlled. The regime change is real but the
            within-regime ranking requires multivariate control to observe correctly.
        ```
        
        ```
        INTERACTION_CANDIDATE: IC3 [NEW — from skeptic Finding 4]
          components: [vehicleRefrigAgeMonths, vehicleRefrigModel]
          component_hypotheses: [BDT1]
          joint_signal_evidence: Skeptic's within-model analysis shows Daikin_RKN retains
            a 0.119 age gradient (15.6% at 60+ months vs 6.3% at <30 months) while 8/9
            other models show non-significant gradients. The population-level sel_freq of
            0.0 masks a severe model-specific degradation effect.
          expected_interaction_form: regime_change
          reasoning: Different refrigeration models have different degradation curves.
            Daikin_RKN units may have design characteristics (compressor type, refrigerant
            system, insulation material) that make them more susceptible to age-related
            performance decline than other models. The age effect is real but conditional
            on model type.
          verification_note: This interaction was anticipated in the original BDT1 block
            (recommended GRF with vehicleRefrigModel as modifier) and confirmed by
            skeptic's targeted analysis.
        ```
        """;

}
