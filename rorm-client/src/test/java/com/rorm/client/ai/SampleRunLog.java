package com.rorm.client.ai;

public interface SampleRunLog {
    String SAMPLE_ANCHOR = """
        Anchor entity: containers
        Perspective: Equipment condition and aging
        
        Source entities (anchor + reachable enrichment):
        - containers (insulation specs, manufacture date, physical dimensions)
        - vehicles (refrigeration unit, cooling capacity, insulation rating, cargo specs)
        - fleet_transitions (generation history, retirement/replacement events)
        - maintenance_log (maintenance timing, triggers, vehicle generation at service)
        - nodes (facility refrigeration system, redundancy, infrastructure age)
        - node_ops_logs (pre-dispatch facility conditions — requires date-matched aggregation)
        - loggers (measurement metadata — calibration dates)
        
        Join path to outcome: containers → shipments (via containerId) → excursionFlag
        
        This anchor sees the physical state of equipment before dispatch. Explore
        degradation mechanisms, fleet cohort effects, maintenance timing effects,
        and facility-condition interactions. All attributes on source entities are
        eligible as treatments. Everything else is confounder.
        """;

    String SAMPLE_DOMAIN_RESEARCH = """
        ## Domain Identification
        
        - **Domain**: Pharmaceutical Cold Chain Logistics — Last-Mile Distribution
        - **Sub-domain**: Temperature-controlled delivery of refrigerated pharmaceutical products (2–8°C) via fleet distribution from hub/node warehouses to recipient sites (pharmacies, clinics, hospitals)
        - **Confidence**: HIGH
        - This is a well-studied domain with extensive regulatory guidance (WHO, FDA, EMA), industry benchmarks (IQVIA, Pharmaceutical Cold Chain Council), and peer-reviewed research on temperature excursion causes, rates, and interventions.
        
        ---
        
        ## Reference Class Baselines
        
        ### Excursion Rate (Shipment-Level)
        - **Definition**: Percentage of shipments where the observed temperature deviates outside the approved storage range (typically 2–8°C) for a defined duration.
        - **Typical range**:
        About 30% of cold chain shipments experience temperature excursions, and an estimated 20% of temperature-sensitive healthcare products are damaged or degraded during distribution due to poor cold chain management (IQVIA Institute, 2019).
        
        12% of pharmaceutical shipments still experience temperature excursions despite technological advances.
        
        25% of vaccines reach their destination degraded because of incorrect shipping, and 30% of scrapped pharmaceuticals can be attributed to logistics issues alone.
        
        - **Interpretation**: The 12–30% range reflects wide variation depending on supply chain maturity, geography, and product type. A well-managed last-mile pharmaceutical cold chain in a developed market should target <5% excursion rates; 10–15% is common; >20% signals systemic issues.
        - **Sources**: IQVIA Institute (2019), Pharmaceutical Cold Chain Council (2023), WHO, European Pharmaceutical Manufacturer (2025)
        
        ### Financial Impact
        Failures in the supply cold chain cost the biopharma industry an estimated $35 billion annually.
        
        
        ### Vaccine-Specific Waste
        The WHO estimates that before COVID-19, up to 50% of vaccines were wasted globally each year due to lack of proper temperature control and logistics.
         (Note: This is a global figure including developing regions with limited infrastructure — not representative of a domestic US fleet operation.)
        
        ### Best-in-Class Delivery Rate
        One pharmaceutical logistics provider improved on-time, in-specification delivery rates from 91.7% to 99.2% using Lean principles.
         This suggests a realistic achievable target of ~99%+ in-spec delivery for mature operations.
        
        ---
        
        ## Known Causal Drivers
        
        ### 1. Ambient Temperature / Seasonal Heat Exposure
        - **Direction**: Higher ambient temperature → higher excursion risk (above 8°C breaches)
        - **Mechanism**: External heat load overwhelms container/vehicle insulation and refrigeration capacity, especially during handoffs (loading, unloading, door openings)
        - **Evidence**: WELL_ESTABLISHED
        - **Effect size**:
        With an ambient temperature rising just above 70°F (21°C), temperatures could soar past 130°F (50°C) on a tarmac or exposed loading area.
         Summer months in hot climate zones (Southwest US, South Central US) likely show 2–5× excursion rates vs. mild seasons. This is the single most confounding variable in the dataset.
        - **Nonlinearity**: Effect is nonlinear — excursion risk accelerates above ~30°C ambient as refrigeration units approach capacity limits.
        
        ### 2. Equipment Failure / Refrigeration Unit Health
        - **Direction**: Degraded reefer unit → higher excursion risk
        - **Mechanism**: Compressor inefficiency, refrigerant leaks, condenser/evaporator coil fouling, electrical faults
        - **Evidence**: WELL_ESTABLISHED
        - **Effect size**:
        Predictive maintenance software claims to reduce refrigeration unit breakdowns by 50%.
        
        Preventative measures significantly reduce the likelihood of costly downtimes and repairs.
         Older refrigeration units with higher `vehicleRefrigAgeMonths` are expected to show degraded performance; typical reefer unit lifespan is 7–12 years, with performance loss accelerating after 5–7 years.
        - **Key source**: Thermo King, Carrier, and Daikin service documentation; fleet management literature.
        
        ### 3. Container Insulation Type and Degradation
        - **Direction**: Lower insulation quality or aged insulation → higher excursion risk
        - **Mechanism**: Thermal conductivity increases as insulation ages (gas diffusion in foams, vacuum loss in VIPs)
        - **Evidence**: WELL_ESTABLISHED
        - **Effect size**:
        VIP panels have a thermal conductivity of between 3 and 7 mW/m·K.
        
        PU rigid foam panels offer thermal conductivity of approximately 0.020–0.024 W/m·K, compared to XPS boards at 0.028–0.035 W/m·K.
         VIP panels provide roughly 3–7× better insulation than conventional foams. Container aging (`containerAgeMonths`) is expected to degrade performance, with PUR/XPS foams losing 5–15% of R-value over 5 years, and VIP panels potentially losing more if vacuum integrity degrades.
        - **Ranking for your dataset**: VIP_panel > PIR_foam ≈ PUR_foam > XPS_foam in thermal performance
        
        ### 4. Multi-Stop Route Complexity (Stop Sequence & Door Openings)
        - **Direction**: More stops → more door openings → more heat ingress → higher excursion risk for later stops
        - **Mechanism**: Each stop requires door opening, allowing ambient air into the cargo compartment. Cumulative thermal load increases with stop count.
        - **Evidence**: WELL_ESTABLISHED
        -
        Door sensors alert whenever cargo doors are opened or closed, helping to monitor security and prevent unnecessary temperature excursions.
         Packages delivered at later stops in a multi-stop route face compounded risk — expect `stopSequence` and `routeTotalStops` to be meaningful predictors.
        - **Effect size**: Industry rule of thumb — each door opening event at 30°C+ ambient can raise internal container temperature by 0.5–2°C depending on duration. Stops 4+ on a long route in summer conditions are high-risk.
        
        ### 5. Receiving Delay at Destination Site
        - **Direction**: Longer receiving delay → higher excursion risk
        - **Mechanism**: Product sits unrefrigerated or in a non-temperature-controlled dock area while awaiting receiving staff.
        - **Evidence**: WELL_ESTABLISHED
        -
        The "last mile" period is critical — medicines can be delayed or left exposed at the last stop in the cold chain.
         After-hours arrivals (`isAfterHoursArrival`) and sites without temperature-controlled docks (`receivingDockTempControlled`) compound this risk.
        - **Effect size**: 15–60 minutes of uncontrolled exposure at high ambient temps can push product above 8°C threshold.
        
        ### 6. Packaging and Loading Practices
        - **Direction**: Improper loading → poor airflow → uneven cooling → excursion
        - **Mechanism**: Poor pallet positioning blocks cold air circulation; loading warm product into pre-cooled compartment
        - **Evidence**: DOCUMENTED
        -
        Improper loading practices, doors left open, or inaccurate configuration of monitoring equipment remain among the most preventable causes.
        
        - **Effect size**: Hard to quantify precisely, but `palletPosition` and `productMassAtStopKg` in your data could proxy for airflow/thermal mass effects.
        
        ### 7. Vehicle Age and Insulation Rating
        - **Direction**: Older vehicle / lower insulation rating → higher excursion risk
        - **Mechanism**: Vehicle body insulation degrades over time (cracks, moisture absorption, panel delamination), reducing the thermal envelope
        - **Evidence**: DOCUMENTED
        - **Effect size**: Vehicle insulation degrades roughly 3–5% per year. A vehicle with `vehicleInsulationRating` in the lower range would be significantly more vulnerable, especially combined with high ambient temperatures.
        
        ### 8. Human Error and Process Failures
        - **Direction**: SOPs not followed → higher excursion risk
        - **Mechanism**:
        The most common causes are temperature fluctuations, delayed transport, handling errors and exposure to extreme environmental conditions.
        
        - **Evidence**: WELL_ESTABLISHED
        - **Effect size**: Hard to observe directly in sensor data, but manifests as unexplained excursions not attributable to equipment or environment.
        
        ### 9. Pre-Departure Temperature (Starting Condition)
        - **Direction**: Higher pre-departure temperature → less thermal buffer → higher excursion risk
        - **Mechanism**: If product starts closer to 8°C rather than 2–4°C, any heat exposure has less margin before breach
        - **Evidence**: WELL_ESTABLISHED
        - **Effect size**: Starting at 6°C vs. 3°C means roughly half the thermal buffer; directly observable via `preDepartureTempC` in your data.
        
        ---
        
        ## Common Pitfalls
        
        ### 1. Ambient Temperature Confounds Everything
        - **Description**: Ambient temperature correlates with season, region, route duration, receiving delay behavior, and refrigeration unit strain simultaneously. Any factor-level analysis that doesn't control for ambient temperature may produce spurious associations.
        - **How to detect/control**: Always include `ambientTempAtDispatchC` and `ambientTempAtArrivalC` as control variables. Stratify analyses by climate zone or season.
        - **Severity**: **HIGH**
        
        ### 2. Logger Drift Creating False Excursions (or Masking Real Ones)
        - **Description**:
        Regular calibration of multi-use temperature loggers is recommended to avoid drift in readings over time.
         Your data includes `driftRateCPerMonth` and `loggerMonthsSinceCal` — loggers with high drift × long time since calibration may report systematically biased readings.
        - **How to detect/control**: Analyze excursion rates stratified by logger age and drift rate. A positive drift rate could create false excursions; a negative drift rate could mask real ones.
        - **Severity**: **HIGH** — this is a measurement validity issue that could undermine all other analyses.
        
        ### 3. Survivorship Bias in Container/Vehicle Analysis
        - **Description**: Retired vehicles and containers (`retired = true`) are removed from service, possibly *because* they caused excursions. Analyzing only active equipment understates the true relationship between equipment age and excursion risk.
        - **How to detect/control**: Include retired equipment in historical analyses. Check whether `fleet_transitions` events correlate with pre-transition excursion rates.
        - **Severity**: MEDIUM
        
        ### 4. Simpson's Paradox Across Regions/Hubs
        - **Description**: A factor that appears protective overall (e.g., a certain vehicle model) might be confounded by regional assignment. If that model is preferentially deployed to mild-climate hubs, it will look superior even if it's equivalent.
        - **How to detect/control**: Stratify all equipment comparisons by hub/region/climate zone.
        - **Severity**: **HIGH**
        
        ### 5. Excursion Definition Sensitivity
        -
        A cold chain breach is defined as exposure to temperatures outside of 2°C to 8°C for longer than 15 minutes or any period below 2°C.
         The excursion definition varies by regulator and product. Small changes in threshold (e.g., 8.0°C vs 8.5°C, or 10 min vs 15 min duration threshold) can dramatically change excursion counts.
        - **How to detect/control**: Verify the exact definition used in `excursionFlag` and `excursion_events`. Check whether the `durationMin` and `peakTempObservedC` in excursion events follow a consistent rule.
        - **Severity**: MEDIUM
        
        ### 6. Reverse Causation in Maintenance Triggers
        - **Description**: `maintenance_log.trigger = 'alarm'` suggests maintenance was *caused by* an excursion/problem, not that maintenance *prevented* one. Naively correlating maintenance with excursion rates may show maintenance "causes" excursions.
        - **How to detect/control**: Separate pre-scheduled maintenance (`trigger = 'time'`, `'pre_summer'`) from reactive maintenance (`'alarm'`, `'both'`). Only preventive maintenance is a valid intervention to study.
        - **Severity**: MEDIUM
        
        ### 7. Seasonal Confounding with Product Mix
        - **Description**: If certain products (Class A vs B) or SKUs are shipped more in certain seasons, seasonal excursion patterns might reflect product mix rather than temperature alone.
        - **How to detect/control**: Control for `productClass` and `sku` in seasonal analyses.
        - **Severity**: LOW-MEDIUM
        
        ---
        
        ## Domain-Specific Metric Definitions
        
        ### Temperature Excursion
        - **Standard definition**:
        A temperature excursion occurs when a pharmaceutical product is exposed to temperatures outside of its approved range for a specific period, potentially impacting its stability and effectiveness.
        
        - **Measurement**: Recorded by in-container data loggers at configured sampling intervals. Your data captures this in `excursion_events` (with `startTimestamp`, `endTimestamp`, `durationMin`, `peakTempObservedC`) and as a binary `excursionFlag` on `shipments`.
        - **Ambiguity**: The exact threshold (time + temperature) that triggers an excursion flag may differ by product stability class. Products with `stabilityClass = A` likely have tighter tolerances than `B`. The `excursionToleranceMin` field on `product_skus` confirms this varies by SKU.
        
        ### Excursion Rate
        - **Definition**: Proportion of shipments (or container journeys) with at least one excursion event. Can be measured at shipment level, journey level, or site level.
        - **Ambiguity**: Whether a single journey with 3 brief excursions counts the same as one with a single prolonged excursion. Duration-weighted excursion metrics may be more informative.
        
        ### Receiving Delay
        - **Definition**: Time between arrival at site and formal receipt/handoff. Captured in `receivingDelayMin`.
        - **Relevance**: Directly contributes to uncontrolled temperature exposure.
        
        ### Refrigeration Health Percentage
        - **Definition**: `nodeRefrigHealthPct` / `refrigHealthPct` — likely a composite metric of refrigeration system operational efficiency, though the exact calculation is not externally standardized.
        
        ---
        
        ## Geospatial Context
        
        Your data spans **7 US regions** with distinct climate profiles relevant to cold chain risk:
        
        | Region | Climate Relevance |
        |--------|------------------|
        | **Southwest_AZ** | Extreme summer heat (40°C+), highest excursion risk region |
        | **SouthCentral_TX** | Hot, humid summers; high heat load on equipment |
        | **Southeast_GA** | Hot, humid; moderate-to-high risk |
        | **Midwest_IN** | Continental — hot summers, cold winters (both-direction excursion risk) |
        | **Mountain_CO** | Moderate; altitude effects on reefer performance possible |
        | **Northeast_NJ** | Moderate summers, cold winters |
        | **PacificNW_OR** | Mild; lowest expected heat-related excursion risk |
        
        The regional distribution means **climate zone is a critical stratification variable** — any analysis comparing hubs, vehicles, or containers must account for regional climate differences.
        
        ---
        
        ## Reference Class Limitations
        
        1. **Most published benchmarks are global or include developing markets**, where cold chain infrastructure is far less mature. A US-based fleet operation with modern vehicles and real-time monitoring should perform substantially better than global averages (12–30%).
        
        2. **Effect sizes for specific interventions** (e.g., VIP vs. PUR containers, preventive maintenance schedules) are **poorly quantified in peer-reviewed literature** for last-mile pharmaceutical delivery specifically. Most evidence comes from food cold chain, air freight, or vaccine distribution in developing countries — these transfer imperfectly.
        
        3. **Logger drift effects on measured excursion rates** are a known measurement issue but are rarely quantified in the literature. Your dataset uniquely captures drift rate and calibration age, enabling analysis that most published studies cannot perform.
        
        4. **Multi-stop route thermal dynamics** are complex and depend on specific vehicle geometry, cargo load, door open duration, and ambient conditions. Published rules of thumb (0.5–2°C per door opening) are rough approximations.
        
        5. **The dataset appears to cover a single product temperature range (2–8°C)**, which simplifies analysis but limits generalization to broader cold chain contexts involving frozen or ambient-controlled products.
        """;

    String SAMPLE_SURVEY = """
        ## Entity Census
        
        | Entity | Rows | Attrs | Flags | Key Fields |
        |---|---|---|---|---|
        | `shipments` | 563,028 | ~49 | has_temporal, wide_table | shipmentId (PK), excursionFlag **(TARGET: 6.5%)**, containerJourneyId, vehicleId, containerId, hubId, date, disposition |
        | `receiving_logs` | 563,028 | 12 | has_temporal | shipmentId (FK→shipments), acceptanceStatus, tempOnReceiptC, receivingDelayMin |
        | `stop_events` | 563,028 | 10 | has_temporal | containerJourneyId (FK→container_journeys), stopSequence, doorOpenTimestamp, doorCloseTimestamp |
        | `node_ops_logs` | 116,928 | 10 | has_temporal | nodeId, timestamp (2015–2024), refrigHealthPct, powerStatus, internalTempC |
        | `container_journeys` | 94,354 | 17 | has_temporal | containerJourneyId (PK), hubId, vehicleId, containerId, loggerId, dispatchTimestamp |
        | `excursion_events` | 10,255 | 10 | has_temporal | containerJourneyId (FK), containerId (FK), peakTempObservedC (8.0–16.2°C), durationMin (6–231 min) |
        | `maintenance_log` | 6,104 | 7 | has_temporal | vehicleId (FK→vehicles), trigger, refrigUnitModel |
        | `logger_devices` | 800 | 3 | — | loggerId, initialCalibrationDate |
        | `containers` | 772 | 9 | has_temporal | containerId (PK), insulationType, wallAM2, doorAM2, manufactureDate, retired |
        | `vehicles` | 299 | 10 | has_temporal | vehicleId (PK), makeModel, refrigUnitModel, reeferKwRated, insulationRating, purchaseDate, retired |
        | `recipient_sites` | 257 | 12 | — | nodeId, lat, lon, urbanRural, openTime/closeTime, hasTempDock |
        | `site` | 257 | 2 | small_table | id (PK), type (clinic/hospital/pharmacy) |
        | `fleet_transitions` | 191 | 9 | has_temporal | hubId (FK→hubs), date, newVehicleId, retiredVehicleId, newGeneration |
        | `route` | 49 | 2 | small_table | id (PK), name |
        | `product_skus` | 25 | 8 | small_table | skuId, name, stabilityClass (A/B), excursionToleranceMin, shelfLifeMonths, manufacturer |
        | `sku` | 25 | 2 | small_table | id (PK), name — **duplicate of product_skus concept** |
        | `manufacturers` | 8 | 5 | small_table | mfrId, name, country, gmpLevel |
        | `hub_interventions` | 17 | 4 | small_table, has_temporal | hubId (FK→hubs), date, interventionNumber |
        | `hubs` | 7 | 7 | small_table | hubId (PK), region (7 distinct), city, state, climateZone (5 types), lat/lon |
        | `cold_nodes` | 28 | 12 | small_table | hubId (FK→hubs), region, refrigSystemType, refrigSystemAgeMonthsAtStart, capacityPallets, hasRedundancy |
        
        ---
        
        ## Relationship Topology
        
        ```
        hubs (7) → cold_nodes (28)            1:N via cold_nodes.hubId
        hubs (7) → container_journeys (94K)   1:N via container_journeys.hubId
        hubs (7) → shipments (563K)           1:N via shipments.hubId
        hubs (7) → fleet_transitions (191)    1:N via fleet_transitions.hubId
        hubs (7) → hub_interventions (17)     1:N via hub_interventions.hubId
        
        vehicles (299) → container_journeys (94K)   1:N via container_journeys.vehicleId
        vehicles (299) → shipments (563K)           1:N via shipments.vehicleId
        vehicles (299) → maintenance_log (6K)       1:N via maintenance_log.vehicleId
        
        containers (772) → container_journeys (94K)  1:N via container_journeys.containerId
        containers (772) → excursion_events (10K)    1:N via excursion_events.containerId
        containers (772) → shipments (563K)          1:N via shipments.containerId
        
        container_journeys (94K) → shipments (563K)       1:N via shipments.containerJourneyId
        container_journeys (94K) → stop_events (563K)     1:N via stop_events.containerJourneyId
        container_journeys (94K) → excursion_events (10K) 1:N via excursion_events.containerJourneyId
        
        shipments (563K) → receiving_logs (563K)  1:1 via receiving_logs.shipmentId
        
        site (257) ← recipient_sites (257)     1:1 via recipient_sites.site
        site (257) ← receiving_logs (563K)     1:N via receiving_logs.site
        
        route (49) ← shipments (563K)          1:N via shipments.route
        
        sku (25) ← shipments (563K)            1:N via shipments.sku
        ```
        
        ---
        
        ## Geospatial Anchors
        
        Hierarchy detected across three entity layers:
        
        | Level | Field | Cardinality |
        |---|---|---|
        | Region | `hubs.region`, `cold_nodes.region` | 7 (Midwest_IN, Mountain_CO, Northeast_NJ, PacificNW_OR, SouthCentral_TX, Southeast_GA, Southwest_AZ) |
        | Climate Zone | `hubs.climateZone` | 5 distinct zones (humid_continental, humid_subtropical, hot_desert, oceanic, semi_arid) |
        | Hub | `hubs.hubId` | 7 hubs (1 per region) |
        | Cold Node | `cold_nodes.nodeId` | 28 nodes (4 per region / hub) |
        | Recipient Site | `recipient_sites.lat`, `recipient_sites.lon` | 257 sites — urban/suburban/rural stratification present |
        
        Point coordinates available on: `hubs`, `cold_nodes`, `recipient_sites`.
        
        ---
        
        ## Measurement Metadata Annotations
        
        **`shipments`** (primary observation grain):
        | Field | Classification | Reasoning |
        |---|---|---|
        | `loggerId` | **process_metadata** | Identity of the temperature recording device, not of the shipment |
        | `loggerMonthsSinceCal` | **process_metadata** | Calibration state of the recording instrument |
        | `vehicleRefrigModel`, `vehicleRefrigAgeMonths`, `vehicleReeferKwRated`, `vehicleInsulationRating`, `vehicleMakeModel`, `vehicleCargoVolumeM3` | **measurement_of_subject** | Attributes of the vehicle being analyzed; replacing the logger wouldn't change these |
        | `containerInsulationType`, `containerAgeMonths` | **measurement_of_subject** | Attributes of the container being analyzed |
        | `nodeRefrigHealthPct`, `nodePowerStatus` | **measurement_of_subject** | State of the cold node (the infrastructure under study) |
        | `maxTempObservedC`, `preDepartureTempC`, `ambientTempAtDispatchC`, `ambientTempAtArrivalC` | **measurement_of_subject** | Recorded temperatures — observed values, not instrument identity |
        
        **`container_journeys`**:
        | Field | Classification |
        |---|---|
        | `loggerId` | **process_metadata** — identifies logger assigned to this journey |
        | `vehicleInsulationRating`, `vehicleReeferKwRated`, `vehicleRefrigAgeMonths`, `containerInsulationType`, `containerAgeMonths`, `vehicleCargoVolumeM3` | **measurement_of_subject** |
        
        **`logger_devices`** — **entire entity is process_metadata**: This is the recording apparatus registry. `loggerId`, `initialCalibrationDate` characterize the instrument, not the shipment.
        
        **`excursion_events`**:
        | Field | Classification |
        |---|---|
        | `loggerId` | **process_metadata** — which logger detected the excursion |
        | `peakTempObservedC`, `durationMin`, `startTimestamp`, `endTimestamp` | **measurement_of_subject** |
        
        **`cold_nodes`** — ambiguous case noted:
        - `refrigSystemType`, `refrigSystemAgeMonthsAtStart` → **measurement_of_subject**: the cold node IS the subject being monitored; its refrigeration system is an attribute of the subject, not the recording instrument.
        
        ---
        
        ## Intrinsic Column Flags
        
        | Field | Entity | Flag Reasoning |
        |---|---|---|
        | `vehicleBreakdown` | `shipments` | Boolean, **100% FALSE** across 563,028 records over 10 years. No breakdowns recorded in any shipment. Either this event type was never simulated/populated, or the field is a label placeholder with no real data. |
        | `preMaintExcursionCount` | `maintenance_log` | Declared type is boolean, labeled as a "count". **100% FALSE** across all 6,104 records. A pre-maintenance excursion count that is universally zero is implausible; this field appears unpopulated. |
        
        ---
        
        ## Data Quality Red Flags
        
        | Entity | Field | Issue | Severity | Quantified Impact |
        |---|---|---|---|---|
        | `maintenance_log` | `preMaintExcursionCount` | All-false boolean (0 positives, 6,104 records) | **HIGH** | Field is non-informative; any analysis relying on it yields null signal |
        | `shipments` | `vehicleBreakdown` | All-false boolean (0 positives, 563,028 records) | **HIGH** | Field is non-informative across full dataset; breakdown impact on excursions cannot be assessed |
        | `shipments` | `loggerMonthsSinceCal` | Negative minimum (-2.7 months) | **MEDIUM** | Physically impossible if defined as time since last calibration; 563,028 records have this field but integrity unclear |
        | `sku` / `product_skus` | — | Parallel tables for same concept | **LOW** | Both have 25 rows; `sku` has only id+name; `product_skus` has full attributes (stability class, tolerance, shelf life). Shipments reference `sku` (thin table), missing rich `product_skus` attributes at join. |
        | `hub_interventions` | — | Only 17 rows across 7 hubs and 10 years | **LOW** | Very sparse event table; may be incomplete or represent only major interventions |
        | `shipments` | `rejectionReason` | Populated in only 5,320 of 7,354 rejected records (~72% coverage) | **LOW** | ~28% of rejections lack a reason code; cannot fully classify rejection causes |
        
        ---
        
        ## 1:N Cardinality Warnings
        
        | Relationship | Ratio | Warning |
        |---|---|---|
        | `hubs` → `shipments` | **80,432 shipments/hub** | Joining hubs attributes to shipments without aggregation inflates every hub-level attribute 80K× |
        | `hubs` → `container_journeys` | **13,479 journeys/hub** | Same inflation risk at journey grain |
        | `containers` → `excursion_events` | **13.3 excursions/container** | Aggregation required before joining back to containers; multi-excursion containers will fan out |
        | `cold_nodes` → `node_ops_logs` | **4,176 log entries/node** | Time-series grain; must aggregate (e.g., daily avg) before joining to shipments |
        | `vehicles` → `maintenance_log` | **20.4 maintenance records/vehicle** | Joining maintenance to shipments requires temporal alignment (which maintenance record applies at time of shipment?) |
        | `container_journeys` → `shipments` | **5.97 shipments/journey** | Each journey visits ~6 stops; joining journey-level attributes to shipments is safe but each journey attribute repeats 6× |
        | `container_journeys` → `stop_events` | **5.97 stop_events/journey** | Same ratio — stop_events and shipments appear to be parallel records at the same stop grain |
        
        ---
        
        ## Schema Summary
        
        ```
        SCALE: 563K shipments | 94K journeys | 563K stop_events (1:1 with shipments) | 116K node_ops_logs
        SPAN: 2015-01-01 → 2024-12-31 (10 years)
        TARGET: shipments.excursionFlag = 6.5% positive (36,589 / 563,028)
        OUTCOME CHAIN: shipments.disposition → accepted(98.5%) / rejected(1.3%) / quarantined(0.2%)
                       rejectionReason → all values are temp_excursion_*min patterns
        
        GEO: region(7) → hub(7) → cold_node(28, 4/hub) → recipient_site(257)
             climateZone(5 types across 7 hubs) | lat/lon on hubs, cold_nodes, recipient_sites
        
        CORE GRAPH:
          hubs(7) → cold_nodes(28) → node_ops_logs(116K, temporal, refrigHealth+powerStatus)
          hubs(7) → container_journeys(94K) → shipments(563K, wide=49 attrs, TARGET=excursionFlag)
                                            → stop_events(563K, door-open timestamps)
                                            → excursion_events(10K, peak 8–16°C, dur 6–231min)
          vehicles(299, makeModel+refrigUnit+insulation) → maintenance_log(6K, trigger+model)
          containers(772, insulationType+age+wallAM2) → excursion_events(10K)
          logger_devices(800, calDate) [process metadata registry]
          product_skus(25, stabilityClass A/B + excursionToleranceMin) — NOT joined to shipments directly
          sku(25, thin) ← shipments — MISSING rich SKU attributes at shipment grain
        
        PROCESS METADATA: shipments.loggerId, shipments.loggerMonthsSinceCal, container_journeys.loggerId,
                          excursion_events.loggerId, logger_devices (entire entity)
        
        DEAD FIELDS: shipments.vehicleBreakdown (all false), maintenance_log.preMaintExcursionCount (all false)
        IMPOSSIBLE VALUES: loggerMonthsSinceCal min = -2.7 (negative calibration age)
        
        FAN-OUT DANGER: hubs→shipments (80K:1), containers→excursions (13.3:1), vehicles→maintenance (20.4:1)
        """;

    String SAMPLE_GENERATOR_V1 = """
        ## Causal Hypothesis Report: Equipment Condition & Aging â†’ Excursion Risk
        
        ### Step 1â€“2: Exploratory Inspection & Column Classification
        
        **Observation unit**: Shipment (n=563,028), each a stop on a container journey.
        **Target**: excursionFlag = 6.5% positive (36,589 excursions).
        
        **Column Classification (anchor-scope tagged):**
        
        | Column | Classification | Anchor Scope |
        |--------|---------------|-------------|
        | containerInsulationType, containerAgeMonths, wallAM2, doorAM2 | Candidate feature | **Anchor-internal** (containers) |
        | vehicleRefrigModel, vehicleMakeModel, vehicleRefrigAgeMonths, vehicleInsulationRating, vehicleReeferKwRated, vehicleCargoVolumeM3 | Candidate feature | **Anchor-internal** (vehicles) |
        | nodeRefrigHealthPct, nodePowerStatus | Candidate feature | **Anchor-internal** (nodes) |
        | hasRedundancy, refrigSystemType, refrigSystemAgeMonthsAtStart | Candidate feature | **Anchor-internal** (nodes/cold_nodes) |
        | preDepartureTempC | **Near-outcome mediator** | Partially anchor-internal (equipmentâ†’preDepâ†’excursion) |
        | ambientTempAtDispatchC, ambientTempAtArrivalC, dispatchMonth | Exogenous/uncontrollable | Anchor-external |
        | receivingDelayMin, isAfterHoursArrival, receivingDockTempControlled, siteType, siteUrbanRural | Anchor-external actionable | Anchor-external |
        | stopSequence, routeTotalStops, routeTotalDriveHours, routeTotalAirMiles, palletPosition, productMassAtStopKg | Route mechanism | Anchor-external |
        | dayOfWeek, dispatchHour, productClass | Temporal/product confounder | Anchor-external |
        | disposition, rejectionReason, maxTempObservedC, reroutedFlag, packagesRemainingAfter | **Post-outcome â€” EXCLUDED** | N/A |
        | loggerMonthsSinceCal, loggerId | **Process metadata** | N/A |
        | vehicleBreakdown | Dead field (all false) â€” EXCLUDED | N/A |
        
        ---
        
        ### Rare-Event Anomaly Scan
        
        ```
        RARE_EVENT_FINDING:
          feature: nodePowerStatus
          rare_level: outage
          n: 256
          pct_of_total: 0.045%
          outcome_rate_at_rare: 79.3%
          outcome_rate_baseline: 6.5%
          rate_ratio: 12.2x
          anchor_internal: yes (cold_nodes)
          mechanism: Power outage disables facility refrigeration. Product stored at the
            cold node warms rapidly without active cooling. Pre-departure temperature rises
            far above 8Â°C, guaranteeing excursion during transit regardless of container
            or vehicle quality.
          tautology_check: No â€” power outage is an infrastructure event, not a consequence
            of excursion.
          conditional_context:
            - condition: hasRedundancy=false + outage
              finding: 83.6% excursion rate (n=116)
            - condition: hasRedundancy=true + outage
              finding: 75.7% excursion rate (n=140) â€” redundancy provides ~8pp protection
                but does NOT prevent catastrophic failure
            - condition: hasRedundancy=false + normal
              finding: 4.7% excursion rate â€” lower than hasRedundancy=true (8.2%) due to
                Simpson's Paradox (non-redundant nodes are at cooler hubs)
          prescriptive_implication: Install backup power (UPS/generator) at all cold nodes.
            Current redundancy provides marginal protection (~8pp) â€” insufficient.
          downstream_action: VERIFY_AND_PRESCRIBE
        ```
        
        ```
        RARE_EVENT_FINDING:
          feature: nodeRefrigHealthPct
          rare_level: <50%
          n: 2,572
          pct_of_total: 0.46%
          outcome_rate_at_rare: 56.6%
          outcome_rate_baseline: 6.5%
          rate_ratio: 8.7x
          anchor_internal: yes (cold_nodes/nodes)
          mechanism: Severe refrigeration system degradation (health <50%) causes facility
            internal temperature to rise above safe range. Product staged for dispatch
            absorbs heat, starting the journey with compromised thermal buffer.
            nodeRefrigHealthPct mean=99.5% with stddev=5.5 â€” these are extreme tail events
            (compressor failure, refrigerant loss).
          tautology_check: No â€” facility refrig health is measured before dispatch, not a
            consequence of shipment excursion.
          conditional_context:
            - condition: nodeRefrigHealthPct 50-90%
              finding: 21.9% excursion rate (n=2,380) â€” gradient confirmed
            - condition: nodeRefrigHealthPct 90-95%
              finding: 5.7% excursion rate (n=317) â€” essentially at baseline
            - condition: nodeRefrigHealthPct 95-100%
              finding: 6.2% excursion rate (n=557,759) â€” baseline
          prescriptive_implication: Implement threshold alarm at 90% refrig health. Below 90%,
            hold dispatch until refrigeration restored. Below 50% is catastrophic â€” stop all
            outbound shipments.
          downstream_action: DIRECT_PRESCRIPTIVE
        ```
        
        ---
        
        ### Iteration Chain & Hypotheses
        
        ```
        HYPOTHESIS:
          treatment: containerInsulationType
          treatment_scope: containers (anchor entity)
          treatment_form: categorical
          threshold_value: N/A (4-level categorical)
          threshold_convergence: N/A
          outcome: excursionFlag
          expected_direction: VIP_panel â†’ lowest risk; PIR_foam/XPS_foam â†’ highest risk
          effect_modifiers: [ambientTempAtDispatchC, stopSequence]
          independently_actionable_mediators: [preDepartureTempC â€” container insulation
            quality affects thermal buffering during pre-departure staging]
          ANCHOR_GROUNDING:
            anchor_entity: containers
            reachable_enrichment: [vehicles, fleet_transitions, maintenance_log, nodes,
              node_ops_logs, loggers]
            iteration_chain:
              - run_1: {target: excursionFlag, saturated: [], top_feature: preDepartureTempC,
                  classification: A (near-outcome mediator),
                  mechanism: "pre-departure thermal state",
                  action: "saturate + flip target"}
              - run_2: {target: excursionFlag,
                  saturated: [preDepartureTempC, ambientTempAtDispatchC, ambientTempAtArrivalC,
                    dispatchMonth, routeTotalStops, routeTotalDriveHours, routeTotalAirMiles,
                    stopSequence, receivingDelayMin, isAfterHoursArrival, receivingDockTempControlled],
                  top_feature: vehicleMakeModel/vehicleRefrigModel,
                  classification: D (anchor-internal proxy categorical),
                  mechanism: "vehicle equipment cohort",
                  action: "saturate categoricals to unbundle"}
              - run_3: {target: preDepartureTempC (FLIP), saturated: [],
                  top_feature: ambientTempAtDispatchC,
                  classification: B (exogenous),
                  mechanism: "climate â†’ pre-departure temp",
                  action: "note equipment variables surface (vehicleInsulationRating rank=2
                    in LightGBM, vehicleRefrigAgeMonths sel_freq=0.9 in LightGBM)"}
              - run_4: {target: excursionFlag,
                  saturated: [preDepartureTempC, climate mechanism, route mechanism,
                    receiving mechanism, vehicleMakeModel, vehicleRefrigModel, dayOfWeek],
                  top_feature: containerInsulationType (sel_freq=1.0 all models),
                  classification: D (anchor-internal actionable),
                  mechanism: "container thermal barrier quality",
                  action: "SHAP analysis + report as hypothesis"}
            saturated_mechanisms:
              - mechanism: pre-departure thermal state
                features_saturated: [preDepartureTempC]
                reason: mediator â€” screens all upstream causes
              - mechanism: climate/geography
                features_saturated: [ambientTempAtDispatchC, ambientTempAtArrivalC, dispatchMonth]
                reason: exogenous
              - mechanism: route complexity
                features_saturated: [routeTotalStops, routeTotalDriveHours, routeTotalAirMiles,
                  stopSequence]
                reason: anchor-external
              - mechanism: receiving-side
                features_saturated: [receivingDelayMin, isAfterHoursArrival, receivingDockTempControlled]
                reason: anchor-external
              - mechanism: vehicle equipment cohort (proxy)
                features_saturated: [vehicleMakeModel, vehicleRefrigModel]
                reason: proxy categorical â€” saturated to unbundle continuous components
              - mechanism: temporal pattern
                features_saturated: [dayOfWeek]
                reason: anchor-external
          DAG_EDGES:
            - (containerInsulationType, excursionFlag)
            - (containerInsulationType, preDepartureTempC)
            - (ambientTempAtDispatchC, excursionFlag)
            - (ambientTempAtDispatchC, containerInsulationType [effect modification])
            - (preDepartureTempC, excursionFlag)
          CONFOUNDER_REASONING:
            - ambientTempAtDispatchC: Causes both the treatment effect magnitude and outcome directly. Saturated.
            - vehicleRefrigModel: Different vehicle types may be paired with different container types AND affect outcome. Saturated.
            - stopSequence/palletPosition: More stops = more door openings = more heat ingress. Saturated via route mechanism.
          EVIDENCE:
            stability_score: Run 4 â€” sel_freq=1.0 consensus across all 3 model families
              (linear rank=2.3, elastic_net rank=1.9, lightgbm rank=2.3)
            shap_curve_form: categorical â€” VIP_panel |SHAP|=0.145 (most protective),
              PIR_foam=0.174, XPS_foam=0.170, PUR_foam=0.162
            domain_alignment: PARTIALLY ALIGNED â€” domain predicts VIP > PIR â‰ˆ PUR > XPS,
              data shows VIP > PUR > XPS > PIR. PIR/XPS ordering differs from textbook
              thermal conductivity rankings, possibly reflecting real-world degradation
              patterns or manufacturing quality variance.
            raw_rates: VIP_panel=6.09%, PUR_foam=6.34%, XPS_foam=6.76%, PIR_foam=6.95%
            effect_size: ~0.86pp spread (6.09â†’6.95) across types â€” modest but consistent
              after saturating all major confounders
            n_per_level: VIP=154,697 / PUR=149,348 / XPS=163,396 / PIR=95,587 â€” all well-powered
        ```
        
        ```
        HYPOTHESIS:
          treatment: vehicleEquipmentCohort (vehicleMakeModel Ã— vehicleRefrigModel)
          treatment_scope: vehicles (anchor entity)
          treatment_form: categorical interaction
          threshold_value: N/A
          outcome: excursionFlag
          expected_direction: Newer generation (Gen3: E-Transit, NPR_HD_2022, ProMaster_3500
            with Advancer_A400/ZeSTIA/Vector_HE_19) â†’ lowest risk
          effect_modifiers: [ambientTempAtDispatchC, routeTotalDriveHours]
          independently_actionable_mediators: [preDepartureTempC]
          ANCHOR_GROUNDING:
            anchor_entity: vehicles
            iteration_chain: run_2 surfaced this as dominant after saturating mediator
            saturated_mechanisms: [pre-departure thermal state, climate, route, receiving]
          EVIDENCE:
            stability_score: Run 2 â€” vehicleMakeModel sel_freq=1.0 (rank=1.33),
              vehicleRefrigModel sel_freq=1.0 (rank=1.67)
            shap_curve_form: categorical ranges â€” vehicleMakeModel [0.53, 0.66],
              vehicleRefrigModel modest spread across models
            raw_rates: Gen3 vehicles 3.98-4.80% vs Gen1 vehicles 7.20-8.43%
            note: Vehicle categoricals are PROXY variables bundling generation, age,
              specs, and deployment patterns. The continuous components
              (vehicleRefrigAgeMonths, vehicleInsulationRating) show negligible
              independent signal after saturating the categoricals (Run 4: sel_freq=0.0).
              The actionable lever is fleet procurement, not equipment aging per se.
        ```
        
        ```
        HYPOTHESIS:
          treatment: nodePowerStatus
          treatment_scope: nodes/cold_nodes (anchor entity)
          treatment_form: categorical (binary: normal/outage)
          outcome: excursionFlag
          expected_direction: outage â†’ catastrophic excursion risk (79.3%)
          EVIDENCE:
            See RARE_EVENT_FINDING above.
            Run 4 stability: sel_freq=0.33, confirming signal persists after full saturation
            SHAP: outage |SHAP|=2.33 (massive), normal |SHAP|=0.02
            Note: This is a rare event (n=256, 0.045%) with extreme effect size.
            Redundancy provides only marginal protection (83.6%â†’75.7%).
        ```
        
        ```
        HYPOTHESIS:
          treatment: nodeRefrigHealthPct
          treatment_scope: nodes (anchor entity)
          treatment_form: continuous with threshold
          threshold_value: 90%
          threshold_convergence: nonlinear tail â€” extreme effect below 50%
          outcome: excursionFlag
          expected_direction: below 90% â†’ rapidly increasing risk
          EVIDENCE:
            See RARE_EVENT_FINDING above.
            SHAP: nonlinear extreme tail effect below 50%
            Gradient: 95-100% â†’ 6.2%, 90-95% â†’ 5.7%, 50-90% â†’ 21.9%, <50% â†’ 56.6%
        ```
        
        ### Non-Findings (Important Nulls)
        
        - **containerAgeMonths**: No degradation signal. Excursion rates flat across 0-72+ months (5.77-6.74%). SHAP range [-0.001, 0.001]. Container age does NOT predict excursion risk â€” insulation type matters, aging does not.
        
        - **vehicleRefrigAgeMonths**: Raw gradient exists (5.4%â†’11.7% over 0-72+ months) but is FULLY ABSORBED by vehicle categorical proxies. After saturating vehicleMakeModel + vehicleRefrigModel (Run 4), sel_freq drops to 0.0. SHAP breakpoint at 28.6 months has magnitudes of only [-0.003, +0.011]. The aging signal is a proxy for generation/model differences, not a direct causal effect of equipment degradation.
        
        - **vehicleInsulationRating**: U-shaped SHAP with breakpoint at 0.783. The counterintuitive high-rating risk is confounded by hot-climate hub deployment. After full saturation, sel_freq=0.0 in Run 4.
        
        - **vehicleReeferKwRated**: SHAP range [-0.006, 0.015] â€” essentially flat. Cooling capacity is a non-factor after accounting for vehicle model identity.
        """;

    String SAMPLE_GENERATOR_SCEPTIC = """
        ## VERIFICATION REPORT
        
        ---
        
        ### VERIFICATION 1
        - **plan_item**: #1, containerInsulationType raw rates (Pattern #0)
        - **claim**: VIP_panel=6.09%, PUR_foam=6.34%, XPS_foam=6.76%, PIR_foam=6.95%
        - **generator_evidence**: Run 4 SHAP + raw rates
        - **alternative_path**: Direct query on shipments table
        - **generator_number**: VIP=6.09%, PUR=6.34%, XPS=6.76%, PIR=6.95%
        - **skeptic_number**: VIP=6.093%, PUR=6.342%, XPS=6.760%, PIR=6.954%
        - **delta**: <0.01pp on all — exact match
        - **pattern**: 0
        - **verdict**: SUPPORTED
        - **material**: no
        - **executor_note**: Raw rates verified to 3 decimal places. N per level: VIP=154,697 / PUR=149,348 / XPS=163,396 / PIR=95,587 — confirmed.
        
        ---
        
        ### VERIFICATION 2
        - **plan_item**: #2, containerInsulationType direction across confounders (Pattern #3)
        - **claim**: VIP_panel → lowest risk; PIR_foam → highest risk
        - **generator_evidence**: Run 4 raw rates after saturation
        - **alternative_path**: Stratified by region (7 regions × 4 insulation types = 28 cells)
        - **generator_number**: VIP always best, PIR always worst
        - **skeptic_number**: Direction **REVERSES** in Southwest_AZ and Southeast_GA
        - **delta**: In Southwest_AZ: VIP_panel=17.08% (WORST), PUR_foam=10.56% (BEST). Gap = 6.5pp reversal. In Southeast_GA: VIP=8.34%, PIR=8.46%, PUR=8.18%, XPS=7.92% — XPS_foam is best, essentially flat.
        - **pattern**: 3
        - **verdict**: CONDITIONAL
        - **material**: yes
        - **executor_note**: The VIP_panel advantage reverses in hot-climate regions. In Southwest_AZ (n=84,924), VIP has the HIGHEST excursion rate at 17.08%, while PUR_foam has the lowest at 10.56%. Within Southwest_AZ nodes: at NOD_025, VIP=16.2% vs XPS=8.7%; at NOD_024, VIP=21.0% vs PUR=14.4%. VIP containers at these nodes also face higher ambient temperatures (avg_ambient 25.1°C for VIP vs 24.3°C for PUR), suggesting deployment bias within regions. However, even a 0.8°C differential cannot explain a 6.5pp excursion rate difference. The hypothesis should state: "VIP_panel is most protective in cool/temperate regions but worst in extreme heat (Southwest_AZ)." The aggregate 0.86pp advantage is a Simpson's-like artifact that masks strong regional heterogeneity.
        
        ---
        
        ### VERIFICATION 3
        - **plan_item**: #3, vehicleRefrigAgeMonths absorbed by vehicleMakeModel (Pattern #2)
        - **claim**: Aging signal fully absorbed by vehicle categorical proxies
        - **generator_evidence**: Run 4 sel_freq=0.0 after saturating vehicleMakeModel
        - **alternative_path**: verifyProxyAbsorption — within each makeModel, compute corr(vehicleRefrigAgeMonths, excursionFlag)
        - **generator_number**: sel_freq=0.0 (fully absorbed)
        - **skeptic_number**: Max within-model gradient = 0.083 (Ford_E-450, n=75,702); no model reaches significance
        - **delta**: All 9 within-model gradients below significance threshold
        - **pattern**: 2
        - **verdict**: SUPPORTED
        - **material**: no
        - **executor_note**: Absorption confirmed across all 9 vehicle models. The largest residual signal is in Ford_E-450 (gradient=0.083) and Freightliner_M2_106 (0.072) — weak but non-zero. The stratified gradient analysis confirms: within-model slopes range from -0.0005 to +0.0013 per month, all negligible. Safe to exclude.
        
        ---
        
        ### VERIFICATION 4
        - **plan_item**: #4, vehicleInsulationRating absorbed by vehicleMakeModel (Pattern #2)
        - **claim**: U-shaped SHAP confounded by hot-climate deployment; fully absorbed after saturation
        - **generator_evidence**: Run 4 sel_freq=0.0
        - **alternative_path**: verifyProxyAbsorption within each makeModel
        - **generator_number**: sel_freq=0.0
        - **skeptic_number**: Max within-model gradient = 0.098 (Ford_E-450); no model significant
        - **delta**: All 9 within-model gradients insignificant
        - **pattern**: 2
        - **verdict**: SUPPORTED
        - **material**: no
        - **executor_note**: Absorption confirmed. Ford_E-450 and Freightliner_M2_106 show marginal residual (0.077–0.098) but below significance.
        
        ---
        
        ### VERIFICATION 5
        - **plan_item**: #5, containerAgeMonths no signal (Pattern #5)
        - **claim**: Container age does NOT predict excursion risk; excursion rates flat across 0-72+ months
        - **generator_evidence**: SHAP range [-0.001, 0.001]
        - **alternative_path**: (A) verifyAbsence in PIR_foam subpopulation (worst insulation), (B) verifyAbsence in high ambient (>30°C), (C) stratifiedGradient by insulation type
        - **generator_number**: 0 (no signal)
        - **skeptic_number**: (A) gradient=0.032 (PIR, n=95,587, CONFIRMED_NULL); (B) gradient=0.007 (high-temp, n=63,016, CONFIRMED_NULL); (C) per-type slopes: PIR=0.00048/month, PUR=0.00013, VIP=-0.00006, XPS=0.00008
        - **delta**: Negligible
        - **pattern**: 5
        - **verdict**: SUPPORTED
        - **material**: no
        - **executor_note**: Null confirmed across multiple subpopulations. Even in PIR_foam (where degradation should be most visible) and in high-heat conditions, container age has no meaningful gradient. VIP actually shows a trivially negative slope (older containers slightly better — likely a vintage effect, not degradation). Survivorship check also returned UNLIKELY (retired vs active delta = -0.3pp).
        
        ---
        
        ### VERIFICATION 6
        - **plan_item**: #6, vehicleReeferKwRated flat (Pattern #5)
        - **claim**: Cooling capacity is a non-factor after vehicle model identity
        - **generator_evidence**: SHAP range [-0.006, 0.015]
        - **alternative_path**: verifyAbsence in long-route subpopulation (>4.5 drive hours, n=37,528)
        - **generator_number**: ~0
        - **skeptic_number**: gradient = -0.012 (CONFIRMED_NULL)
        - **delta**: 0
        - **pattern**: 5
        - **verdict**: SUPPORTED
        - **material**: no
        - **executor_note**: Even on the longest routes where cooling capacity should matter most, no signal detected.
        
        ---
        
        ### VERIFICATION 7
        - **plan_item**: #7, nodePowerStatus sample size (Pattern #11)
        - **claim**: Outage → 79.3% excursion rate (n=256)
        - **generator_evidence**: Raw count
        - **alternative_path**: verifySampleSizeAdequacy
        - **generator_number**: 79.3%
        - **skeptic_number**: 79.3% confirmed; detectable effect = 28.1pp (n=256 is underpowered for fine-grained effects)
        - **delta**: 0
        - **pattern**: 11
        - **verdict**: SUPPORTED (rate verified) but PARTIALLY_ADEQUATE (n=256 is borderline)
        - **material**: no
        - **executor_note**: The rate is real and massive (79.3%). While the stratum is technically underpowered per the tool (detectable effect 28pp), the observed effect of 79.3% vs 6.5% baseline is so extreme that the signal is unambiguous despite small n. The CI width of ±5pp at 79.3% still leaves the rate far above baseline.
        
        ---
        
        ### VERIFICATION 8
        - **plan_item**: #8, nodeRefrigHealthPct threshold at 90% (Pattern threshold)
        - **claim**: Threshold alarm at 90% refrig health; below 50% is catastrophic
        - **generator_evidence**: Gradient: 95-100%→6.2%, 90-95%→5.7%, 50-90%→21.9%, <50%→56.6%
        - **alternative_path**: thresholdLocation with 20 bins
        - **generator_number**: Claimed threshold 90%
        - **skeptic_number**: Detected inflection at 24.9% (bin midpoint); largest delta at 0→12% bin boundary
        - **delta**: 65pp between claimed (90%) and detected threshold
        - **pattern**: threshold
        - **verdict**: OVERSTATED
        - **material**: yes
        - **executor_note**: The 90% threshold is overly conservative. The data shows the real inflection occurs much lower. Bin-by-bin rates: 100%→6.2%, 92%→5.7%, 88%→7.3%, 82%→3.9%, 78%→8.3%, 72%→8.2%, 67%→28.8%, 61%→28%, 57%→41%, 52%→40%, 48%→50%, 42%→50%, 37%→53%, 32%→69%, 27%→49%, 22%→48%, 17%→55%, 12%→75%, 0%→79%. The major step change occurs around **70%** (from ~8% to ~29%), not at 90%. Between 90-100% the excursion rate is flat at 5.7-6.2%. The generator's 90% alarm would trigger on many non-risky facilities. A threshold of 70% better captures the actual inflection point. The <50% catastrophic claim holds (56.6% confirmed).
        
        ---
        
        ### VERIFICATION 9
        - **plan_item**: #9, rare event independence (Pattern C3)
        - **claim**: nodePowerStatus=outage and nodeRefrigHealthPct<50% are separate rare events
        - **generator_evidence**: Reported separately with n=256 and n=2,572
        - **alternative_path**: crossTabulation of power status × health buckets
        - **generator_number**: Two independent rare events
        - **skeptic_number**: ALL 256 outages fall within health<50% — power outage is a **STRICT SUBSET** of low refrig health
        - **delta**: 100% overlap — not independent at all
        - **pattern**: C3 (Rare Event Independence)
        - **verdict**: OVERSTATED
        - **material**: yes
        - **executor_note**: Power outage and refrig health <50% are NOT independent events. Every single outage record (256/256) has nodeRefrigHealthPct < 50%. This makes sense mechanically — when power goes out, refrigeration health sensors read 0% or near-0%. The 2,572 records with health <50% decompose into: 256 with outage (79.3% excursion) + 2,316 with normal power (54.1% excursion). The generator's two rare event findings should be consolidated: there is ONE underlying phenomenon (facility refrigeration failure) with two severities: total power outage (n=256, 79.3%) and partial degradation without outage (n=2,316, 54.1%). Reporting them separately inflates the apparent discovery count and risks double-counting in prescriptive recommendations.
        
        ---
        
        ### VERIFICATION 10
        - **plan_item**: #10, containerInsulationType ecological fallacy (Pattern #6)
        - **claim**: VIP_panel is most protective (6.09% vs 6.95%)
        - **generator_evidence**: Marginal (unstratified) rates across types
        - **alternative_path**: verifyEcologicalFallacy with nodeId grouping (28 groups)
        - **generator_number**: Between-group slope = 0.0031
        - **skeptic_number**: Within-group slope = 0.0016 (47% smaller)
        - **delta**: -0.0015 (47% relative reduction)
        - **pattern**: 6
        - **verdict**: ECOLOGICAL
        - **material**: yes
        - **executor_note**: The marginal insulation type effect is inflated by ~47% due to ecological fallacy. Within nodes, the insulation type gradient is roughly halved. Different insulation types are non-randomly deployed to different nodes, and node-level variation (ambient conditions, facility quality) confounds the between-type comparison. The 0.86pp effect size should be cited as closer to ~0.45pp within comparable conditions. Combined with the Southwest_AZ direction reversal, the containerInsulationType hypothesis is materially weaker than presented.
        
        ---
        
        ### VERIFICATION 11
        - **plan_item**: #11, preDepartureTempC mediates containerInsulationType→excursion (Pattern #1)
        - **claim**: Container insulation quality affects thermal buffering during pre-departure staging, mediated through preDepartureTempC
        - **generator_evidence**: DAG edge (containerInsulationType, preDepartureTempC), FLIP TARGET run 3
        - **alternative_path**: verifyScreeningMediation
        - **generator_number**: 0.86pp effect size claimed via insulation→preDep→excursion pathway
        - **skeptic_number**: corr(insulation, preDep) = 0.016 (near-zero); corr(insulation, outcome) = 0.013; partial_corr(insulation, outcome | preDep) = 0.004
        - **delta**: corr(insulation, preDep) is negligible
        - **pattern**: 1
        - **verdict**: INCOMPLETE
        - **material**: yes
        - **executor_note**: The claimed DAG edge (containerInsulationType → preDepartureTempC) has almost no empirical support: corr(insulation, preDep) = 0.016. Container insulation type barely affects pre-departure temperature. However, controlling for preDepartureTempC does reduce the insulation→excursion correlation by ~70% (0.013 → 0.004), suggesting preDepartureTempC partially CONFOUNDS the relationship rather than mediating it. The distinction matters: if preDep is a confounder (both insulation type assignment and preDep are determined by facility conditions), then the "direct" insulation effect of 0.004 is the real effect, and it's negligible. The generator should re-examine whether preDepartureTempC is a mediator or a confounder in this DAG.
        
        ---
        
        ### VERIFICATION 12
        - **plan_item**: #12, container survivorship bias (Pattern #8)
        - **claim**: containerAgeMonths shows no degradation signal
        - **generator_evidence**: SHAP [-0.001, 0.001]
        - **alternative_path**: verifySurvivorshipBias using containerId.retired flag
        - **generator_number**: No degradation
        - **skeptic_number**: Retired containers: 6.47% excursion (n=506,393); Active: 6.78% (n=56,635); delta = -0.31pp
        - **delta**: -0.31pp (retired actually LOWER than active)
        - **pattern**: 8
        - **verdict**: SUPPORTED (survivorship bias unlikely)
        - **material**: no
        - **executor_note**: No survivorship bias detected. Retired containers have a slightly lower excursion rate than active ones (-0.31pp), opposite to what survivorship bias would produce. The null containerAgeMonths finding is genuine.
        
        ---
        
        ### VERIFICATION 13
        - **plan_item**: #13, vehicle survivorship bias (Pattern #8)
        - **claim**: vehicleRefrigAgeMonths aging signal is fully absorbed by model proxy
        - **generator_evidence**: sel_freq=0.0 after saturation
        - **alternative_path**: verifySurvivorshipBias using vehicleId.retired flag
        - **generator_number**: No independent aging effect
        - **skeptic_number**: Active vehicles: 6.17% (n=143,033); Retired: 6.61% (n=419,995); delta = +0.44pp
        - **delta**: 0.44pp
        - **pattern**: 8
        - **verdict**: SUPPORTED (survivorship bias unlikely)
        - **material**: no
        - **executor_note**: Retired vehicles have slightly higher excursion rates (+0.44pp), consistent with older/worse equipment being retired. The magnitude is too small to indicate meaningful survivorship bias that would mask a real aging effect.
        
        ---
        
        ### VERIFICATION 14
        - **plan_item**: #14, preDepartureTempC collider check (Pattern #7)
        - **claim**: preDepartureTempC is a mediator to be saturated, not a collider
        - **generator_evidence**: Saturation iteration chain
        - **alternative_path**: verifyColliderConditioning — check if conditioning on preDep induces spurious correlation between insulation and ambient
        - **generator_number**: N/A
        - **skeptic_number**: Unconditional corr(insulation, ambient) = 0.017; Conditional = 0.011; delta = -0.006
        - **delta**: -0.006 (correlation DECREASES slightly)
        - **pattern**: 7
        - **verdict**: SUPPORTED (SAFE — no collider bias)
        - **material**: no
        - **executor_note**: Conditioning on preDepartureTempC does not amplify the insulation-ambient correlation. No collider risk.
        
        ---
        
        ### VERIFICATION 15
        - **plan_item**: #15, confounder completeness for containerInsulationType (Pattern #12)
        - **claim**: All confounders for insulation → excursion are accounted for
        - **generator_evidence**: Saturation of climate, route, receiving, vehicle, temporal
        - **alternative_path**: verifyConfounderCompleteness with 5 candidate unsaturated variables
        - **generator_number**: Complete
        - **skeptic_number**: No missing confounders detected among palletPosition, productMassAtStopKg, loggerMonthsSinceCal, containerAgeMonths, vehicleCargoVolumeM3
        - **delta**: 0
        - **pattern**: 12
        - **verdict**: SUPPORTED
        - **material**: no
        - **executor_note**: No omitted confounder found among tested candidates.
        
        ---
        
        ### VERIFICATION 16
        - **plan_item**: #16, hasRedundancy Simpson's Paradox (Pattern #0)
        - **claim**: hasRedundancy=false+normal: 4.7% vs hasRedundancy=true+normal: 8.2% due to non-redundant nodes being at cooler hubs
        - **generator_evidence**: Conditional rates
        - **alternative_path**: Direct query + ambient temperature comparison
        - **generator_number**: 4.7% vs 8.2%, cooler hubs
        - **skeptic_number**: Non-redundant: 4.67% (avg_ambient=17.56°C); Redundant: 8.18% (avg_ambient=18.43°C)
        - **delta**: Confirmed. Non-redundant nodes are 0.87°C cooler on average.
        - **pattern**: 0
        - **verdict**: SUPPORTED
        - **material**: no
        - **executor_note**: Simpson's Paradox confirmed. The ambient temperature differential (17.6°C vs 18.4°C) is modest but directionally correct. The 3.5pp base rate difference (4.7% vs 8.2%) likely reflects a mix of ambient conditions AND hub operational quality differences, not just temperature alone.
        
        ---
        
        ### VERIFICATION 17
        - **plan_item**: #17, vehicleEquipmentCohort Gen3 vs Gen1 rates (Pattern #0, #6)
        - **claim**: Gen3 vehicles 3.98-4.80% vs Gen1 7.20-8.43%
        - **generator_evidence**: Run 2 vehicle model rates
        - **alternative_path**: Cross-tabulation of makeModel × refrigModel; deployment distribution for anomalous E-Transit × Vector_HE_19
        - **generator_number**: Gen3 best, Gen1 worst
        - **skeptic_number**: Ford_E-Transit × Carrier_Vector_HE_19 = **11.71%** excursion rate (n=5,842) — this is a "Gen3" combination yet has the **WORST** rate of all 27 make×refrig combos. Deployment: HHI=0.33, with 51.7% concentrated at NOD_024 (a high-excursion node, marginal rate 20.8%).
        - **delta**: This Gen3 combo is 6.9pp WORSE than the overall "Gen3" claim of 3.98-4.80%
        - **pattern**: 6 (deployment bias)
        - **verdict**: CONDITIONAL
        - **material**: yes
        - **executor_note**: The Gen3 label obscures massive within-generation heterogeneity. Ford_E-Transit × Carrier_Vector_HE_19 at 11.71% is worse than most "Gen1" vehicles. This outlier is driven by extreme deployment concentration at NOD_024 (51.7% of all these vehicles dispatch from one high-risk node). The generator's "Gen3 = lowest risk" claim needs qualification: it's true on average but the E-Transit × Vector_HE_19 combo is a major exception. The actionable lever is redeployment, not just fleet procurement. Additionally, Ford_E-450 × Thermo_King_T-880R (avg_age=28, "Gen1") achieves 4.05% — better than most "Gen3" combos. The generation classification is an oversimplification.
        
        ---
        
        ### VERIFICATION 18
        - **plan_item**: #18, DAG completeness (Pattern #13)
        - **claim**: DAG_EDGES block is complete
        - **generator_evidence**: 5 DAG edges listed
        - **alternative_path**: verifyDAGCompleteness with all saturated controls, excluded variables, and cross-hypothesis treatments
        - **generator_number**: Complete
        - **skeptic_number**: No undocumented pairwise associations above 0.05 threshold
        - **delta**: 0
        - **pattern**: 13
        - **verdict**: SUPPORTED
        - **material**: no
        - **executor_note**: DAG completeness verified at r=0.05 threshold. No missing edges detected among tested variable pairs.
        
        ---
        
        ### VERIFICATION 19 (Effect Modifier)
        - **plan_item**: Supplementary check on claimed effect modifier
        - **claim**: ambientTempAtDispatchC modifies the containerInsulationType effect
        - **generator_evidence**: Listed as effect_modifier
        - **alternative_path**: verifyEffectModifier across ambient temperature quartiles
        - **generator_number**: Effect modification claimed
        - **skeptic_number**: variance_ratio = 0.94 (constant across strata); min effect = -0.17, max = 0.16
        - **delta**: N/A
        - **pattern**: 4
        - **verdict**: UNSUPPORTED at fine-grained level / PARTIALLY_SUPPORTED at regional level
        - **material**: no
        - **executor_note**: At the granular ambient temperature level (0.1°C increments), the insulation type effect is noise-dominated and shows no systematic modification. However, at the regional level, the Southwest_AZ reversal (Verification 2) does suggest geographic/climate-zone-level modification. The effect modifier is real but operates at a coarser spatial scale than implied.
        
        ---
        
        ## CROSS-CLAIM FINDINGS
        
        ### CROSS_CLAIM 1
        - **type**: RARE_EVENT_OVERLAP (C3)
        - **claims_involved**: RARE_EVENT_FINDING (nodePowerStatus=outage) + RARE_EVENT_FINDING (nodeRefrigHealthPct<50%)
        - **finding**: Power outage is a strict subset of low refrig health. All 256 outages have health<50%. These are not independent discoveries but two views of one phenomenon: facility refrigeration failure. The 2,572 records with health<50% include the 256 outages. Reporting them separately inflates the finding count and could lead to redundant prescriptive actions.
        - **material**: yes
        - **executor_note**: Consolidate into a single RARE_EVENT_FINDING describing facility refrigeration failure with two severity levels: (1) partial degradation, normal power (n=2,316, 54.1% excursion), (2) full power outage (n=256, 79.3% excursion). Adjust prescriptive recommendations accordingly — backup power addresses only the 256-record subset, not the broader 2,316-record degradation issue.
        
        ### CROSS_CLAIM 2
        - **type**: AGGREGATE_INCONSISTENCY (C5)
        - **claims_involved**: HYPOTHESIS 1 (containerInsulationType, 0.86pp effect) + ecological fallacy finding
        - **finding**: The 0.86pp marginal effect is inflated ~47% by ecological fallacy (within-node effect ≈ 0.45pp). Combined with the Southwest_AZ reversal where VIP_panel is the worst performer, the net actionable effect of container insulation type switching is operationally negligible in many regions and potentially harmful in hot climates.
        - **material**: yes
        - **executor_note**: Downgrade the containerInsulationType hypothesis effect size from 0.86pp to ~0.45pp and add scope limitation: "beneficial in temperate/cool regions; potentially counterproductive in extreme heat." The prescriptive recommendation should NOT be universal VIP_panel adoption.
        
        ### CROSS_CLAIM 3
        - **type**: DEPENDENCY (C1)
        - **claims_involved**: HYPOTHESIS 2 (vehicleEquipmentCohort) → "Gen3 = lowest risk" claim depends on uniform deployment
        - **finding**: E-Transit × Carrier_Vector_HE_19 (a Gen3 combo) has the worst excursion rate of all 27 make×refrig combinations (11.71%) due to concentrated deployment at NOD_024. The "Gen3 = best" claim is deployment-confounded. Some Gen1 combos outperform Gen3 combos.
        - **material**: yes
        - **executor_note**: The vehicle generation classification oversimplifies. Prescriptive recommendations should be at the make×refrig level, not generation level, and must account for deployment location.
        
        ---
        
        ## ARCHAEOLOGY FINDINGS
        
        ### ARCHAEOLOGY 1
        - **type**: NOTICED_BUT_UNREPORTED
        - **location**: Generator output, CONFOUNDER_REASONING section
        - **observation**: The generator notes that vehicleRefrigModel and containerInsulationType may be non-randomly paired (vehicleRefrigModel "may be paired with different container types") but does not quantify this or test whether container-vehicle assignment is random across hubs. The deployment distribution analysis reveals systematic non-random assignment (different insulation types dominate at different nodes with HHI ranging 0.053-0.055).
        - **impact**: The non-random assignment of container types to nodes is a core confound that the generator acknowledged qualitatively but did not quantify. This directly enables the ecological fallacy identified in Verification 10.
        
        ### ARCHAEOLOGY 2
        - **type**: MISQUOTED (potential)
        - **location**: nodeRefrigHealthPct threshold claim
        - **observation**: Generator claims "threshold alarm at 90%" but the data shows 90-95% health has 5.7% excursion rate — essentially at or below the 6.2% baseline. The threshold location analysis shows the real inflection is around 70%, not 90%. The generator may have conflated "where the gradient starts" with "where the gradient becomes operationally significant."
        - **impact**: The 90% threshold would produce excessive false alarms. Recommending 70% instead would be more operationally sound.
        
        ---
        
        ## COVERAGE SUMMARY
        
        | # | Plan Item | Status | Verdict |
        |---|-----------|--------|---------|
        | 1 | containerInsulationType raw rates | TESTED | SUPPORTED |
        | 2 | Treatment direction by region | TESTED | CONDITIONAL — reversal in Southwest_AZ |
        | 3 | vehicleRefrigAgeMonths absorption | TESTED | SUPPORTED |
        | 4 | vehicleInsulationRating absorption | TESTED | SUPPORTED |
        | 5 | containerAgeMonths absence | TESTED (3 subpops) | CONFIRMED_NULL |
        | 6 | vehicleReeferKwRated absence | TESTED (2 subpops) | CONFIRMED_NULL |
        | 7 | nodePowerStatus sample size | TESTED | ADEQUATE (effect so large n=256 suffices) |
        | 8 | nodeRefrigHealthPct threshold | TESTED | OVERSTATED (90% → 70% real inflection) |
        | 9 | Rare event independence | TESTED | OVERLAP — outage ⊂ health<50% |
        | 10 | Ecological fallacy (insulation) | TESTED | ECOLOGICAL (47% inflation) |
        | 11 | Mediation through preDepartureTempC | TESTED | INCOMPLETE — DAG edge unsupported |
        | 12 | Container survivorship | TESTED | SURVIVORSHIP_UNLIKELY |
        | 13 | Vehicle survivorship | TESTED | SURVIVORSHIP_UNLIKELY |
        | 14 | Collider check (preDep) | TESTED | SAFE |
        | 15 | Confounder completeness | TESTED | COMPLETE |
        | 16 | hasRedundancy Simpson's Paradox | TESTED | SUPPORTED |
        | 17 | Vehicle cohort Gen3 vs Gen1 | TESTED | CONDITIONAL — E-Transit×VHE19 outlier |
        | 18 | DAG completeness | TESTED | DAG_COMPLETE |
        | 19 | Effect modifier (ambient × insulation) | TESTED | UNSUPPORTED at granular level |
        
        **Material findings requiring generator response:**
        1. **Southwest_AZ direction reversal** — VIP_panel is worst in hot climates (CONDITIONAL)
        2. **Ecological fallacy** — 47% inflation of insulation type effect (ECOLOGICAL)
        3. **Rare event overlap** — Power outage ⊂ refrig health <50% (consolidate)
        4. **nodeRefrigHealthPct threshold** — 90% is overly conservative; real inflection at ~70%
        5. **preDepartureTempC mediation** — DAG edge (insulation→preDep) has no empirical support (corr=0.016)
        6. **E-Transit × Vector_HE_19 outlier** — Gen3 combo at 11.71% due to deployment bias
        """;

    String SAMPLE_GENERATOR_REVISED = """
        ## COMPLETE REVISED STRUCTURED OUTPUT
        
        ---
        
        ```
        RARE_EVENT_FINDING:
          feature: nodeRefrigHealthPct (with nodePowerStatus as severity indicator)
          rare_level: Facility refrigeration failure (<70% health)
          n: 4,952 total (decomposed below)
          pct_of_total: 0.88%
          outcome_rate_at_rare: see severity tiers below
          outcome_rate_baseline: 6.5%
          anchor_internal: yes (cold_nodes / nodes)
          mechanism: Facility refrigeration system degradation or failure causes cold-node
            internal temperature to rise. Product staged for dispatch absorbs heat, departing
            with compromised thermal buffer. nodeRefrigHealthPct mean=99.5% (stddev=5.5) —
            these are extreme tail events (compressor failure, refrigerant loss, power outage).
            Power outage is a STRICT SUBSET of low refrig health: all 256 outage records have
            health <50%. This is one phenomenon (facility refrigeration failure) with graduated
            severity.
          tautology_check: No — facility refrig health and power status are measured before
            dispatch, not consequences of shipment excursion.
          severity_tiers:
            - tier: Partial degradation (health 50-70%, normal power)
              n: ~2,380
              excursion_rate: 21.9%
              rate_ratio: 3.4x
            - tier: Severe degradation (health <50%, normal power)
              n: 2,316
              excursion_rate: 54.1%
              rate_ratio: 8.3x
            - tier: Full power outage (health <50%, outage)
              n: 256
              excursion_rate: 79.3%
              rate_ratio: 12.2x
          conditional_context:
            - condition: hasRedundancy=true + outage
              finding: 75.7% excursion rate (n=140) — redundancy provides ~8pp protection
                but does NOT prevent catastrophic failure
            - condition: hasRedundancy=false + outage
              finding: 83.6% excursion rate (n=116)
            - condition: hasRedundancy=false + normal power
              finding: 4.67% excursion (avg_ambient 17.56°C) — lower rate than redundant nodes
                (8.18%, avg_ambient 18.43°C) due to Simpson's Paradox: non-redundant nodes
                are deployed at cooler hubs
            - condition: nodeRefrigHealthPct 70-90%
              finding: ~8% excursion — essentially at baseline, no actionable risk
            - condition: nodeRefrigHealthPct 90-100%
              finding: 5.7-6.2% excursion — baseline
          prescriptive_implication:
            - Threshold alarm at 70% refrig health (NOT 90% — the 90-100% range is flat at
              baseline rates). Below 70%, hold outbound dispatch until refrigeration restored.
            - Below 50% is catastrophic — halt all operations.
            - Install backup power (UPS/generator) at all cold nodes to prevent the most
              extreme tier (79.3% excursion). However, backup power addresses only the 256-record
              outage subset; the broader 2,316-record severe degradation requires proactive
              maintenance and refrigerant monitoring.
          downstream_action: DIRECT_PRESCRIPTIVE
        ```
        
        --TRIM_AFTER
        
        ```
        HYPOTHESIS:
          treatment: containerInsulationType
          treatment_scope: containers (anchor entity)
          treatment_form: categorical
          threshold_value: N/A (4-level categorical)
          threshold_convergence: N/A
          outcome: excursionFlag
          expected_direction: VIP_panel → lowest risk; PIR_foam → highest risk
            **SCOPED TO TEMPERATE/COOL CLIMATE ZONES ONLY** (Northeast, Midwest,
            PacificNW, Mountain). Direction REVERSES in extreme heat (Southwest_AZ:
            VIP_panel=17.08% WORST, PUR_foam=10.56% BEST).
          effect_modifiers: [climateZone (regional scale — not continuous ambient temperature;
            variance_ratio=0.94 at granular level confirms no fine-grained modification)]
          independently_actionable_mediators: []
        
          ANCHOR_GROUNDING:
            anchor_entity: containers
            reachable_enrichment: [vehicles, fleet_transitions, maintenance_log, nodes,
              node_ops_logs, loggers]
            iteration_chain:
              - run_1: {target: excursionFlag, saturated: [], top_feature: preDepartureTempC,
                  classification: A (near-outcome mediator),
                  mechanism: "pre-departure thermal state",
                  action: "saturate + flip target"}
              - run_2: {target: excursionFlag,
                  saturated: [preDepartureTempC, ambientTempAtDispatchC, ambientTempAtArrivalC,
                    dispatchMonth, routeTotalStops, routeTotalDriveHours, routeTotalAirMiles,
                    stopSequence, receivingDelayMin, isAfterHoursArrival, receivingDockTempControlled],
                  top_feature: vehicleMakeModel/vehicleRefrigModel,
                  classification: D (anchor-internal proxy categorical),
                  mechanism: "vehicle equipment cohort",
                  action: "saturate categoricals to unbundle"}
              - run_3: {target: preDepartureTempC (FLIP), saturated: [],
                  top_feature: ambientTempAtDispatchC,
                  classification: B (exogenous),
                  mechanism: "climate → pre-departure temp",
                  action: "note equipment variables surface (vehicleInsulationRating rank=2
                    in LightGBM, vehicleRefrigAgeMonths sel_freq=0.9 in LightGBM)"}
              - run_4: {target: excursionFlag,
                  saturated: [preDepartureTempC, climate mechanism, route mechanism,
                    receiving mechanism, vehicleMakeModel, vehicleRefrigModel, dayOfWeek],
                  top_feature: containerInsulationType (sel_freq=1.0 all models),
                  classification: D (anchor-internal actionable),
                  mechanism: "container thermal barrier quality",
                  action: "SHAP analysis + report as hypothesis"}
            saturated_mechanisms:
              - mechanism: pre-departure thermal state
                features_saturated: [preDepartureTempC]
                reason: mediator — screens all upstream causes; safe to saturate because
                  corr(containerInsulationType, preDepartureTempC)=0.016 means insulation
                  type does not operate through pre-departure temperature
              - mechanism: climate/geography
                features_saturated: [ambientTempAtDispatchC, ambientTempAtArrivalC, dispatchMonth]
                reason: exogenous
              - mechanism: route complexity
                features_saturated: [routeTotalStops, routeTotalDriveHours, routeTotalAirMiles,
                  stopSequence]
                reason: anchor-external
              - mechanism: receiving-side
                features_saturated: [receivingDelayMin, isAfterHoursArrival, receivingDockTempControlled]
                reason: anchor-external
              - mechanism: vehicle equipment cohort (proxy)
                features_saturated: [vehicleMakeModel, vehicleRefrigModel]
                reason: proxy categorical — saturated to unbundle continuous components
              - mechanism: temporal pattern
                features_saturated: [dayOfWeek]
                reason: anchor-external
        
          DAG_EDGES:
            - (containerInsulationType, excursionFlag)   # direct transit thermal barrier
            - (preDepartureTempC, excursionFlag)          # dominant near-outcome cause
            - (ambientTempAtDispatchC, preDepartureTempC) # climate drives facility temp
            - (ambientTempAtDispatchC, excursionFlag)     # climate drives transit heat load
            - (nodeRefrigHealthPct, preDepartureTempC)    # facility health → departure temp
            # NOTE: No edge (containerInsulationType → preDepartureTempC).
            # corr=0.016 — container insulation does NOT affect pre-departure temperature.
            # preDepartureTempC is a CONFOUNDER of insulation→excursion, not a mediator:
            # facility conditions (node) determine both deployment patterns (which insulation
            # types are used where) and pre-departure temperature.
        
          CONFOUNDER_REASONING:
            - preDepartureTempC: CONFOUNDER (not mediator) for insulation→excursion.
              corr(insulation, preDep)=0.016 (near-zero), but controlling for preDep
              reduces partial_corr(insulation, excursion) from 0.013 to 0.004 (~70%
              reduction). This means facility conditions that determine preDep also
              correlate with insulation deployment. Saturated.
            - ambientTempAtDispatchC: Exogenous. Drives both facility thermal load and
              transit heat exposure. Saturated.
            - vehicleRefrigModel/vehicleMakeModel: Different vehicle types may be
              non-randomly paired with different container types at different nodes.
              Saturated.
            - Node-level deployment patterns: Different insulation types are non-randomly
              deployed to different cold nodes (HHI=0.053-0.055 across types), enabling
              ecological fallacy. This is the primary confounding mechanism.
        
          ENRICHMENT_JOINS:
            - main_table_key: nodeId
            - join_table: cold_nodes
            - join_key: nodeId
            - value_column: hasRedundancy, refrigSystemType
            - default_value: false (hasRedundancy), NULL (refrigSystemType)
        
          DERIVED_FEATURES:
            - name: containerWallAM2
              computation: containers.wallAM2 via containerId FK
              source_tables: [containers]
              temporal_ordering: static container attribute, pre-outcome
            - name: containerDoorAM2
              computation: containers.doorAM2 via containerId FK
              source_tables: [containers]
              temporal_ordering: static container attribute, pre-outcome
        
          UNMEASURED_VARIABLES:
            - name: days_since_last_maint
              intended_computation: shipment.date - MAX(maintenance_log.date WHERE vehicleId
                matches AND date < shipment.date)
              failure_reason: Engineered successfully but not included in SS query due to
                correlated subquery cost on 563K × 6K join. Verified pattern produces valid
                results (sample: 92, 141 days).
              impact: Maintenance recency may explain residual within-model equipment
                variation. However, Run 4 shows vehicle continuous variables (refrigAge,
                insulationRating) have sel_freq=0.0 after model-proxy saturation, suggesting
                within-model variation is minimal regardless of maintenance.
        
          EVIDENCE:
            stability_score: Run 4 — sel_freq=1.0 consensus across all 3 model families
              (linear rank=2.3, elastic_net rank=1.9, lightgbm rank=2.3)
            shap_curve_form: categorical — VIP_panel |SHAP|=0.145 (most protective),
              PIR_foam=0.174, XPS_foam=0.170, PUR_foam=0.162
            domain_alignment: PARTIALLY ALIGNED — domain predicts VIP > PIR ≈ PUR > XPS;
              data shows VIP > PUR > XPS > PIR in aggregate. PIR/XPS ordering differs from
              textbook thermal conductivity, possibly reflecting real-world degradation or
              manufacturing quality.
            effect_size_marginal: 0.86pp spread (VIP=6.09% → PIR=6.95%) — INFLATED
            effect_size_within_node: ~0.45pp (47% ecological fallacy reduction confirmed
              by within-node slope=0.0016 vs between-group slope=0.0031)
            scope_limitation: Direction REVERSES in Southwest_AZ — VIP_panel=17.08% (WORST),
              PUR_foam=10.56% (BEST). In Southeast_GA, effect is essentially flat (7.92-8.46%).
              The ~0.45pp within-node effect is reliable only in temperate/cool zones.
            n_per_level: VIP=154,697 / PUR=149,348 / XPS=163,396 / PIR=95,587
        
          MEASUREMENT_METADATA:
            Fields flagged as measurement process metadata:
            [loggerMonthsSinceCal, loggerId]
        ```
        
        ```
        HYPOTHESIS:
          treatment: vehicleEquipmentCohort (vehicleMakeModel × vehicleRefrigModel interaction)
          treatment_scope: vehicles (anchor entity)
          treatment_form: categorical interaction (9 makeModels × 9 refrigModels = up to 27
            observed combinations)
          threshold_value: N/A
          threshold_convergence: N/A
          outcome: excursionFlag
          expected_direction: Best performers include specific make×refrig combinations, NOT
            blanket generation labels. Within-generation heterogeneity is extreme.
            Examples from marginal rates:
            - TK_Advancer_A400 = 3.98% (best refrig model avg)
            - Ford_E-450 × TK_T-880R = 4.05% ("Gen1" combo, outperforms most "Gen3")
            - Daikin_RKN = 8.43% (worst refrig model avg)
            - Ford_E-Transit × Carrier_Vector_HE_19 = 11.71% ("Gen3" combo, WORST of all
              27 combinations — driven by 51.7% deployment concentration at NOD_024,
              a high-excursion node at 20.8% marginal rate)
          effect_modifiers: [deployment node/hub — deployment concentration at high-risk
            nodes is the primary source of within-cohort variation]
          independently_actionable_mediators: []
        
          ANCHOR_GROUNDING:
            anchor_entity: vehicles
            reachable_enrichment: [fleet_transitions, maintenance_log, nodes]
            iteration_chain:
              - run_2: {target: excursionFlag,
                  saturated: [preDepartureTempC, climate, route, receiving],
                  top_feature: vehicleMakeModel (sel_freq=1.0, rank=1.33),
                  classification: D (anchor-internal actionable),
                  mechanism: "vehicle equipment cohort",
                  action: "SHAP analysis"}
            saturated_mechanisms:
              - mechanism: pre-departure thermal state
                features_saturated: [preDepartureTempC]
                reason: mediator
              - mechanism: climate/geography
                features_saturated: [ambientTempAtDispatchC, ambientTempAtArrivalC, dispatchMonth]
                reason: exogenous
              - mechanism: route complexity
                features_saturated: [routeTotalStops, routeTotalDriveHours, routeTotalAirMiles,
                  stopSequence]
                reason: anchor-external
              - mechanism: receiving-side
                features_saturated: [receivingDelayMin, isAfterHoursArrival, receivingDockTempControlled]
                reason: anchor-external
        
          DAG_EDGES:
            - (vehicleMakeModel × vehicleRefrigModel, excursionFlag)
            - (deployment_node, vehicleMakeModel × vehicleRefrigModel [assignment])
            - (deployment_node, excursionFlag [via ambient, facility conditions])
            - (ambientTempAtDispatchC, excursionFlag)
            - (preDepartureTempC, excursionFlag)
        
          CONFOUNDER_REASONING:
            - deployment_node: Vehicles are non-randomly assigned to nodes. NOD_024
              concentrates 51.7% of E-Transit × Vector_HE_19 vehicles and has 20.8%
              marginal excursion rate. Node assignment confounds make×refrig → excursion
              because the node drives both the vehicle pairing seen there AND the
              excursion rate through ambient/facility conditions.
            - preDepartureTempC: Mediator (equipment cooling performance → departure temp
              → excursion). Saturated.
            - Climate mechanism: Vehicles deployed to hotter hubs face higher excursion
              rates regardless of equipment specs. Saturated.
        
          EVIDENCE:
            stability_score: Run 2 — vehicleMakeModel sel_freq=1.0 (rank=1.33),
              vehicleRefrigModel sel_freq=1.0 (rank=1.67)
            shap_curve_form: categorical — vehicleMakeModel SHAP range [0.53, 0.66],
              vehicleRefrigModel modest spread
            deployment_bias_caveat: The "Gen3 = lowest risk" generalization is FALSE.
              Ford_E-Transit × Carrier_Vector_HE_19 (Gen3) = 11.71% (WORST of all combos).
              Ford_E-450 × TK_T-880R (Gen1) = 4.05% (outperforms most Gen3).
              Generation-level classification oversimplifies; actionable recommendations
              must be at make×refrig level AND account for deployment location.
            within_model_aging: vehicleRefrigAgeMonths sel_freq=0.0 in Run 4 after
              saturating categoricals. Within-model slopes range -0.0005 to +0.0013/month
              (all negligible). The vehicle effect is model identity, not aging.
        
          MEASUREMENT_METADATA:
            [loggerMonthsSinceCal, loggerId]
        ```
        
        --RESTORE-AFTER
        
        ```
        HYPOTHESIS:
          treatment: nodeRefrigHealthPct
          treatment_scope: nodes / cold_nodes (anchor entity)
          treatment_form: continuous with binary_threshold
          threshold_value: 70% (revised from 90%)
          threshold_convergence: Verifier's 20-bin analysis shows step-change at ~70%:
            72% health → 8.2% excursion, 67% health → 28.8% excursion.
            Generator's original coarse bins confirmed gradient: 95-100% → 6.2%,
            90-95% → 5.7% (both baseline), 50-90% → 21.9% (coarse bin masking the
            70% inflection), <50% → 56.6%.
          outcome: excursionFlag
          expected_direction: -1 (lower health → higher excursion risk)
          effect_modifiers: [nodePowerStatus — power outage amplifies degradation from
            54.1% to 79.3% excursion at health<50%]
          independently_actionable_mediators: [preDepartureTempC — facility health
            degradation raises departure temperature]
        
          ANCHOR_GROUNDING:
            anchor_entity: nodes / cold_nodes
            reachable_enrichment: [node_ops_logs]
            iteration_chain:
              - run_4: {target: excursionFlag,
                  saturated: [preDepartureTempC, climate, route, receiving, vehicleMakeModel,
                    vehicleRefrigModel, dayOfWeek],
                  feature: nodePowerStatus (sel_freq=0.33), nodeRefrigHealthPct,
                  classification: D (anchor-internal),
                  mechanism: "facility refrigeration integrity",
                  action: "report — see consolidated RARE_EVENT_FINDING for extreme tail"}
              - run_3_flip: {target: preDepartureTempC,
                  feature: nodeRefrigHealthPct surfaces with consistent signal across models,
                  mechanism: "facility health → departure temp"}
            saturated_mechanisms:
              - mechanism: pre-departure thermal state
                features_saturated: [preDepartureTempC]
                reason: mediator on this treatment's causal path — saturating shows
                  parallel transit-phase effects; unsaturated Run 1 shows total effect
              - mechanism: climate/geography
                features_saturated: [ambientTempAtDispatchC, ambientTempAtArrivalC, dispatchMonth]
                reason: exogenous
              - mechanism: vehicle equipment cohort
                features_saturated: [vehicleMakeModel, vehicleRefrigModel]
                reason: anchor-internal but separate mechanism
        
          DAG_EDGES:
            - (nodeRefrigHealthPct, excursionFlag)        # direct facility degradation effect
            - (nodeRefrigHealthPct, preDepartureTempC)     # facility health → departure temp
            - (nodePowerStatus, nodeRefrigHealthPct)       # outage → health reads 0%
            - (ambientTempAtDispatchC, nodeRefrigHealthPct [stress factor])
            - (preDepartureTempC, excursionFlag)
        
          CONFOUNDER_REASONING:
            - ambientTempAtDispatchC: Higher ambient temperatures stress facility refrigeration,
              potentially causing health degradation. Also directly drives excursion risk.
              Saturated.
            - hasRedundancy: Redundant nodes have younger refrig systems (24.2 months avg vs
              71.5 months for non-redundant). Redundancy status confounds the node health
              → excursion path. Simpson's Paradox confirmed: non-redundant normal-power
              nodes show LOWER excursion (4.67%) than redundant (8.18%) due to cooler
              hub deployment (avg ambient 17.56°C vs 18.43°C).
        
          EVIDENCE:
            stability_score: Run 4 — nodePowerStatus sel_freq=0.33; nodeRefrigHealthPct
              signal confirmed via RARE_EVENT analysis (see consolidated finding)
            shap_curve_form: nonlinear extreme tail — flat from 70-100%, steep increase
              below 70%, catastrophic below 50%
            threshold_detail:
              - 95-100%: 6.2% excursion (baseline)
              - 90-95%: 5.7% (baseline — 90% threshold would produce false alarms)
              - ~72%: 8.2% (still near baseline)
              - ~67%: 28.8% (INFLECTION — 3.5x jump)
              - ~52%: 40% (severe)
              - <50% normal power: 54.1%
              - <50% + outage: 79.3%
            domain_alignment: Aligned — facility refrigeration failure is a known cold
              chain risk. The 70% threshold is operationally more appropriate than the
              originally claimed 90%.
        
          MEASUREMENT_METADATA:
            [loggerMonthsSinceCal, loggerId]
        ```
        
        ```
        BELOW_DETECTION_THRESHOLD:
          feature: containerAgeMonths
          anchor_entity: containers
          ss_rank_after_saturation: Run 4 — sel_freq=0.0
          selection_frequency: 0.0
          domain_expectation: Container insulation degrades over time through moisture ingress,
            foam cell collapse, and seal deterioration. Older containers should show higher
            excursion rates.
          absence_hypothesis: confirmed_null
          absence_reasoning: Verified across multiple subpopulations — PIR_foam subpopulation
            (slope=0.00048/month, n=95,587), high-ambient >30°C (gradient=0.007), per-type
            slopes all negligible. VIP actually shows trivially negative slope (older containers
            marginally better — vintage effect). Survivorship bias check returned UNLIKELY
            (retired vs active delta = -0.31pp, wrong direction). Container insulation
            type matters but aging within type does not — suggesting containers are replaced
            or repaired before degradation manifests operationally.
          recommended_downstream:
            analysis_type: MONITOR_ONLY
            proposed_modifiers: []
            rationale: Null finding is robust across subpopulations, survivorship check,
              and stratification. No further analysis warranted unless container replacement
              policy changes.
        ```
        
        ```
        BELOW_DETECTION_THRESHOLD:
          feature: vehicleRefrigAgeMonths
          anchor_entity: vehicles
          ss_rank_after_saturation: Run 4 — sel_freq=0.0 (after saturating vehicleMakeModel
            + vehicleRefrigModel)
          selection_frequency: 0.0
          domain_expectation: Refrigeration unit efficiency degrades with age — compressor
            wear, refrigerant loss, condenser fouling. Older units should show higher
            excursion risk.
          absence_hypothesis: screening (proxy absorption)
          absence_reasoning: Raw marginal gradient exists (5.4% at 0-12m → 11.7% at 72+m)
            but is FULLY ABSORBED by vehicleMakeModel + vehicleRefrigModel categoricals.
            Within-model slopes range -0.0005 to +0.0013/month (all negligible, max
            within-model gradient = 0.083 for Ford_E-450, below significance). The age
            gradient is an artifact of model-cohort composition: newer models (Advancer,
            ZeSTIA, Vector_HE_19) are younger by construction, not because aging within
            a model degrades performance.
          recommended_downstream:
            analysis_type: GRF_heterogeneity
            proposed_modifiers: [ambientTempAtDispatchC, vehicleMakeModel]
            rationale: The null within-model finding may mask conditional aging effects
              visible only in extreme heat or specific models. GRF with ambient temperature
              as the heterogeneity dimension could surface model-specific aging thresholds.
        ```
        
        ```
        BELOW_DETECTION_THRESHOLD:
          feature: vehicleInsulationRating
          anchor_entity: vehicles
          ss_rank_after_saturation: Run 4 — sel_freq=0.0
          selection_frequency: 0.0
          domain_expectation: Higher insulation rating should reduce heat ingress during
            transit, lowering excursion risk.
          absence_hypothesis: screening (proxy absorption)
          absence_reasoning: U-shaped SHAP curve in Run 2 with breakpoint at 0.783.
            Counterintuitive high-rating risk is confounded by hot-climate hub deployment
            (higher-rated vehicles deployed to hotter regions). After saturating
            vehicleMakeModel (which bundles insulation rating), sel_freq drops to 0.0.
            Within-model gradients all insignificant (max=0.098 for Ford_E-450).
          recommended_downstream:
            analysis_type: GRF_heterogeneity
            proposed_modifiers: [ambientTempAtDispatchC]
            rationale: Insulation rating should matter more in extreme heat. GRF with
              ambient temperature could surface a conditional effect masked by model proxy.
        ```
        
        ```
        BELOW_DETECTION_THRESHOLD:
          feature: vehicleReeferKwRated
          anchor_entity: vehicles
          ss_rank_after_saturation: Run 2 SHAP range [-0.006, 0.015]; Run 4 not top-ranked
          selection_frequency: low
          domain_expectation: Higher cooling capacity should maintain lower product temperatures,
            especially on longer routes with more door openings.
          absence_hypothesis: confirmed_null
          absence_reasoning: Flat SHAP even in long-route subpopulation (>4.5 drive hours,
            n=37,528, gradient=-0.012, confirmed null). Cooling capacity is fully captured
            by refrigeration model identity and does not contribute independently.
          recommended_downstream:
            analysis_type: MONITOR_ONLY
            proposed_modifiers: []
            rationale: Null confirmed across conditions including worst-case long routes.
        ```
        
        ```
        DOMAIN_DISCREPANCY:
          expected_driver: containerAgeMonths (insulation degradation over time)
          domain_evidence_strength: WELL_ESTABLISHED
          empirical_finding: Zero signal. SHAP [-0.001, 0.001]. Excursion rates flat
            across 0-72+ months (5.77-6.74% with no monotonic trend). Per-insulation-type
            slopes: PIR=0.00048/month, PUR=0.00013, VIP=-0.00006, XPS=0.00008.
          ss_rank: Not ranked in any iteration (sel_freq=0.0 in Runs 1-4)
          absence_hypothesis: measurement_resolution + operational_replacement
          reasoning: Containers may be replaced or repaired on a schedule that prevents
            observable degradation. The fleet contains 772 containers with 71.4% retired —
            suggesting active lifecycle management. Survivorship bias check shows retired
            containers actually have LOWER excursion rate (-0.31pp), confirming retirement
            is not selectively removing the worst performers. The most likely explanation:
            container replacement policy is effective, preventing age-driven degradation
            from manifesting in operational data.
          proposed_resolution:
            method: Compare excursion rates of containers approaching scheduled retirement
              (within 6 months of typical replacement age) vs mid-life containers, stratified
              by insulation type.
            expected_result_if_real: Approaching-retirement containers show elevated excursion
              rates within the worst insulation types (PIR, XPS).
            expected_result_if_spurious: No difference — replacement policy truly prevents
              degradation from manifesting.
        ```
        
        ```
        DOMAIN_DISCREPANCY:
          expected_driver: vehicleRefrigAgeMonths (compressor/refrigerant degradation)
          domain_evidence_strength: WELL_ESTABLISHED
          empirical_finding: Strong marginal gradient (5.4% → 11.7% over 0-72+ months)
            but FULLY ABSORBED by vehicle model categorical proxies. Within-model slopes
            negligible (-0.0005 to +0.0013/month). Survivorship check: retired vehicles
            have 6.61% vs active 6.17% (+0.44pp), too small to indicate bias.
          ss_rank: Run 1 rank=21.88 (sel_freq=0.093); Run 4 sel_freq=0.0
          absence_hypothesis: screening (model proxy absorption)
          reasoning: The apparent aging effect is entirely a composition effect — newer
            refrigeration models (Advancer, ZeSTIA) are younger by construction because
            they entered the fleet more recently. Within any given model, aging does not
            predict excursion risk. This could mean: (a) refrigeration maintenance is
            effective at maintaining performance regardless of age, (b) degradation is
            real but operates through a mechanism already captured by the categorical
            (e.g., model-specific degradation curves that are flat within the observed
            age range), or (c) the age range within models is too narrow to detect
            degradation (newer models have max ~36 months of observations).
          proposed_resolution:
            method: Restrict to the oldest model cohorts (TK T-680Pro, TK T-880R, Carrier
              Supra 860) which have the widest within-model age ranges (0-100 months).
              Within each, fit nonlinear model of excursionFlag ~ refrigAgeMonths,
              stratified by hub to control for deployment.
            expected_result_if_real: Nonlinear threshold at 60-72 months within old models.
            expected_result_if_spurious: Flat curves within all models regardless of age range.
        ```
        
        ```
        DOMAIN_DISCREPANCY:
          expected_driver: containerInsulationType effect universality
          domain_evidence_strength: DOCUMENTED
          empirical_finding: VIP_panel advantage REVERSES in extreme heat. Southwest_AZ:
            VIP=17.08% (WORST) vs PUR=10.56% (BEST). Within-node effect is ~0.45pp
            (47% smaller than marginal 0.86pp due to ecological fallacy from non-random
            deployment of insulation types across nodes).
          ss_rank: Run 4 sel_freq=1.0 (but reflects marginal, not within-node signal)
          absence_hypothesis: conditional_effect + ecological_fallacy
          reasoning: VIP panels have superior thermal conductivity in laboratory conditions,
            but may perform differently in extreme heat. Possible mechanisms for Southwest_AZ
            reversal: (1) VIP panel performance degrades nonlinearly above certain ambient
            temperatures (vacuum loss at extreme heat), (2) deployment bias within
            Southwest_AZ — VIP containers face avg ambient 25.1°C vs PUR at 24.3°C
            (0.8°C differential), but this alone cannot explain 6.5pp difference,
            (3) VIP containers in Southwest may be older or from a different manufacturing
            batch. The DAG edge (containerInsulationType → preDepartureTempC) has no
            empirical support (corr=0.016), meaning insulation type does NOT operate
            through pre-departure temperature but through transit thermal performance.
          proposed_resolution:
            method: GRF heterogeneity analysis with climateZone as modifier variable
              and nodeId as fixed effect to eliminate ecological confounding.
            expected_result_if_real: VIP coefficient flips sign in hot_desert climate zone,
              with clear threshold around 25°C ambient.
            expected_result_if_spurious: Within-node VIP advantage persists in Southwest_AZ
              once deployment bias is fully controlled.
        ```
        """;

    String SAMPLE_COMPILED_H1 = """
        Now I have all the data I need. Let me compile the complete pipeline configuration.
        
        ## PIPELINE CONFIGURATION
        
        ---
        
        ### hypothesis_id
        `containerInsulationType_excursionFlag`
        
        ### treatment
        `containerInsulationType`
        
        ### outcome
        `excursionFlag`
        
        ### treatment_form
        `CATEGORICAL`
        
        ---
        
        ### query
        
        The query produces one row per shipment (563,028 rows), joining `shipments` LEFT JOIN `cold_nodes` on `nodeId`, and deriving `containerWallAM2`/`containerDoorAM2` via the `containerId` FK to `containers`, `region` via the `hubId` FK to `hubs`, and temporal features via EXTRACT.
        
        **Verified:** Row count = 563,028 (matches base table). cold_nodes join: 0 nulls (100% match on all 28 nodeIds). No fan-out.
        
        ```json
        {
          \\"from\\": \\"shipments\\",
          \\"fromAlias\\": \\"s\\",
          \\"joins\\": [
            {
              \\"joinType\\": \\"LEFT\\",
              \\"joinedRoot\\": {\\"rootName\\": \\"cold_nodes\\", \\"alias\\": \\"cn\\"},
              \\"onCondition\\": {
                \\"@type\\": \\"binary\\",
                \\"left\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.nodeId\\"},
                \\"operator\\": \\"EQUALS\\",
                \\"right\\": {\\"@type\\": \\"path\\", \\"path\\": \\"cn.nodeId\\"}
              }
            }
          ],
          \\"selector\\": {
            \\"@type\\": \\"multi\\",
            \\"expressions\\": [
              {\\"alias\\": \\"shipmentId\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.shipmentId\\"}},
              {\\"alias\\": \\"containerInsulationType\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.containerInsulationType\\"}},
              {\\"alias\\": \\"excursionFlag\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.excursionFlag\\"}},
              {\\"alias\\": \\"preDepartureTempC\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.preDepartureTempC\\"}},
              {\\"alias\\": \\"ambientTempAtDispatchC\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.ambientTempAtDispatchC\\"}},
              {\\"alias\\": \\"ambientTempAtArrivalC\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.ambientTempAtArrivalC\\"}},
              {\\"alias\\": \\"nodeId\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.nodeId\\"}},
              {\\"alias\\": \\"vehicleMakeModel\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.vehicleMakeModel\\"}},
              {\\"alias\\": \\"vehicleRefrigModel\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.vehicleRefrigModel\\"}},
              {\\"alias\\": \\"routeTotalStops\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.routeTotalStops\\"}},
              {\\"alias\\": \\"routeTotalDriveHours\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.routeTotalDriveHours\\"}},
              {\\"alias\\": \\"routeTotalAirMiles\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.routeTotalAirMiles\\"}},
              {\\"alias\\": \\"stopSequence\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.stopSequence\\"}},
              {\\"alias\\": \\"receivingDelayMin\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.receivingDelayMin\\"}},
              {\\"alias\\": \\"isAfterHoursArrival\\", \\"expression\\": {\\"@type\\": \\"function\\", \\"functionName\\": \\"CASE\\", \\"arguments\\": [
                {\\"@type\\": \\"binary\\", \\"left\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.isAfterHoursArrival\\"}, \\"operator\\": \\"EQUALS\\", \\"right\\": {\\"@type\\": \\"literal\\", \\"value\\": true}},
                {\\"@type\\": \\"literal\\", \\"value\\": 1},
                {\\"@type\\": \\"literal\\", \\"value\\": 0}
              ]}},
              {\\"alias\\": \\"receivingDockTempControlled\\", \\"expression\\": {\\"@type\\": \\"function\\", \\"functionName\\": \\"CASE\\", \\"arguments\\": [
                {\\"@type\\": \\"binary\\", \\"left\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.receivingDockTempControlled\\"}, \\"operator\\": \\"EQUALS\\", \\"right\\": {\\"@type\\": \\"literal\\", \\"value\\": true}},
                {\\"@type\\": \\"literal\\", \\"value\\": 1},
                {\\"@type\\": \\"literal\\", \\"value\\": 0}
              ]}},
              {\\"alias\\": \\"dayOfWeek\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.dayOfWeek\\"}},
              {\\"alias\\": \\"dispatchMonth\\", \\"expression\\": {\\"@type\\": \\"function\\", \\"functionName\\": \\"EXTRACT\\", \\"arguments\\": [
                {\\"@type\\": \\"literal\\", \\"value\\": \\"MONTH\\"},
                {\\"@type\\": \\"path\\", \\"path\\": \\"s.date\\"}
              ]}},
              {\\"alias\\": \\"dispatchYear\\", \\"expression\\": {\\"@type\\": \\"function\\", \\"functionName\\": \\"EXTRACT\\", \\"arguments\\": [
                {\\"@type\\": \\"literal\\", \\"value\\": \\"YEAR\\"},
                {\\"@type\\": \\"path\\", \\"path\\": \\"s.date\\"}
              ]}},
              {\\"alias\\": \\"nodeRefrigHealthPct\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.nodeRefrigHealthPct\\"}},
              {\\"alias\\": \\"productClass\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.productClass\\"}},
              {\\"alias\\": \\"palletPosition\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.palletPosition\\"}},
              {\\"alias\\": \\"productMassAtStopKg\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.productMassAtStopKg\\"}},
              {\\"alias\\": \\"containerAgeMonths\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.containerAgeMonths\\"}},
              {\\"alias\\": \\"vehicleRefrigAgeMonths\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.vehicleRefrigAgeMonths\\"}},
              {\\"alias\\": \\"vehicleInsulationRating\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.vehicleInsulationRating\\"}},
              {\\"alias\\": \\"vehicleCargoVolumeM3\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.vehicleCargoVolumeM3\\"}},
              {\\"alias\\": \\"vehicleReeferKwRated\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.vehicleReeferKwRated\\"}},
              {\\"alias\\": \\"nodePowerStatus\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.nodePowerStatus\\"}},
              {\\"alias\\": \\"siteType\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.siteType\\"}},
              {\\"alias\\": \\"siteUrbanRural\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.siteUrbanRural\\"}},
              {\\"alias\\": \\"isHosRegulated\\", \\"expression\\": {\\"@type\\": \\"function\\", \\"functionName\\": \\"CASE\\", \\"arguments\\": [
                {\\"@type\\": \\"binary\\", \\"left\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.isHosRegulated\\"}, \\"operator\\": \\"EQUALS\\", \\"right\\": {\\"@type\\": \\"literal\\", \\"value\\": true}},
                {\\"@type\\": \\"literal\\", \\"value\\": 1},
                {\\"@type\\": \\"literal\\", \\"value\\": 0}
              ]}},
              {\\"alias\\": \\"loggerMonthsSinceCal\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.loggerMonthsSinceCal\\"}},
              {\\"alias\\": \\"hasRedundancy\\", \\"expression\\": {\\"@type\\": \\"function\\", \\"functionName\\": \\"CASE\\", \\"arguments\\": [
                {\\"@type\\": \\"binary\\", \\"left\\": {\\"@type\\": \\"path\\", \\"path\\": \\"cn.hasRedundancy\\"}, \\"operator\\": \\"EQUALS\\", \\"right\\": {\\"@type\\": \\"literal\\", \\"value\\": true}},
                {\\"@type\\": \\"literal\\", \\"value\\": 1},
                {\\"@type\\": \\"literal\\", \\"value\\": 0}
              ]}},
              {\\"alias\\": \\"refrigSystemType\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"cn.refrigSystemType\\"}},
              {\\"alias\\": \\"region\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.hubId.region\\"}},
              {\\"alias\\": \\"date\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.date\\"}},
              {\\"alias\\": \\"loggerId\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.loggerId\\"}},
              {\\"alias\\": \\"containerWallAM2\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.containerId.wallAM2\\"}},
              {\\"alias\\": \\"containerDoorAM2\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.containerId.doorAM2\\"}}
            ]
          }
        }
        ```
        
        ### expected_row_count
        563,028 — verified via count query. Matches base shipments table exactly (cold_nodes join is 1:1 per nodeId, no fan-out, 0 nulls).
        
        ### strip_columns
        `[shipmentId]` — row identifier with no analytical value.
        
        ---
        
        ### dag_edges
        
        ```
        nodeId -> containerInsulationType;          # Non-random deployment of insulation types to nodes (HHI=0.053-0.055)
        nodeId -> preDepartureTempC;                # Facility operations/conditions determine departure temp
        nodeId -> ambientTempAtDispatchC;           # Node location determines climate exposure
        dispatchMonth -> ambientTempAtDispatchC;    # Seasonal temperature cycle (Jan 0.09% → Jul 25.7% excursion)
        ambientTempAtDispatchC -> preDepartureTempC; # Climate drives facility thermal load (corr=0.684)
        ambientTempAtDispatchC -> excursionFlag;    # Transit heat load during delivery (corr=0.342)
        nodeRefrigHealthPct -> preDepartureTempC;   # Facility refrig degradation raises departure temp (corr=-0.136)
        preDepartureTempC -> excursionFlag;         # Thermal buffer at departure (corr=0.625, dominant predictor)
        containerInsulationType -> excursionFlag;   # Direct transit thermal barrier effect (treatment)
        stopSequence -> excursionFlag;              # Cumulative heat exposure from door openings (corr=0.240)
        routeTotalDriveHours -> excursionFlag;      # Transit duration determines heat ingress window
        receivingDelayMin -> excursionFlag;         # Post-delivery uncontrolled temperature exposure
        vehicleRefrigModel -> excursionFlag;        # Reefer unit performance during transit
        ```
        
        **Intermediate structure:** nodeId is an upstream fork (3 descendants). ambientTempAtDispatchC and preDepartureTempC are intermediate nodes. Multiple testable d-sep implications exist (e.g., containerInsulationType ⊥ preDepartureTempC | nodeId; containerInsulationType ⊥ dispatchMonth | nodeId).
        
        ---
        
        ### dsep_threshold
        
        **0.034**
        
        Calibration:
        - palletPosition ↔ loggerMonthsSinceCal: corr = 0.00019 (loading vs logger calibration — unrelated domains)
        - productMassAtStopKg ↔ loggerMonthsSinceCal: corr = 0.00026 (product mass vs logger age — unrelated)
        - palletPosition ↔ vehicleRefrigAgeMonths: corr = 0.013 (loading position vs vehicle reefer age — unrelated)
        - stopSequence ↔ containerAgeMonths: corr = 0.017 (route position vs container age — weakly related through deployment)
        
        Max noise-floor correlation: 0.017 (stopSequence ↔ containerAgeMonths, potentially weakly connected through node deployment; conservative inclusion). Threshold = 2.0 × 0.017 = **0.034**.
        
        ---
        
        ### adjustment_set
        
        **W matrix (29 variables):**
        
        1. `preDepartureTempC` — IN W: CONFOUNDER. corr(insulation, preDep)=0.016 near-zero but controls confounding via facility conditions. Partial corr(insulation, excursion) drops ~70% when controlled. Not a mediator (no DAG edge from treatment).
        2. `ambientTempAtDispatchC` — IN W: Exogenous climate variable. Drives both transit heat load and pre-departure temp.
        3. `nodeId` — IN W (MANDATORY, ecological fallacy dimension): 28 levels. Generator confirms within-node effect ~0.45pp vs marginal 0.86pp (47% reduction). Non-random deployment of insulation types across nodes.
        4. `vehicleMakeModel` — IN W: 9 levels. Vehicle types may be non-randomly paired with container types at different nodes.
        5. `vehicleRefrigModel` — IN W: 9 levels. Reefer model confounding (different reefer models deployed to different nodes).
        6. `routeTotalStops` — IN W: Route complexity affects heat exposure independently of treatment.
        7. `routeTotalDriveHours` — IN W: Transit duration determines heat ingress window.
        8. `routeTotalAirMiles` — IN W: Route distance, partially independent of drive hours (corr=0.363).
        9. `stopSequence` — IN W: Within-route position determines cumulative heat exposure.
        10. `receivingDelayMin` — IN W: Post-delivery delay duration.
        11. `isAfterHoursArrival` — IN W: Receiving timing affects delay behavior.
        12. `receivingDockTempControlled` — IN W: Receiving infrastructure.
        13. `dayOfWeek` — IN W: Temporal pattern (6 levels, no Sunday dispatch).
        14. `dispatchMonth` — IN W: Seasonality (extreme variation: Jan 0.09% → Jul 25.7%).
        15. `dispatchYear` — IN W: Secular trend control (2015-2024, 10 years).
        16. `nodeRefrigHealthPct` — IN W: Facility refrigeration health.
        17. `productClass` — IN W: Product sensitivity (A vs B).
        18. `palletPosition` — IN W: Loading position proxy for airflow.
        19. `productMassAtStopKg` — IN W: Thermal mass at stop.
        20. `containerAgeMonths` — IN W: Container aging (confirmed null per generator, but greedy inclusion).
        21. `vehicleRefrigAgeMonths` — IN W: Vehicle reefer age (confirmed screened by proxy, greedy inclusion).
        22. `vehicleInsulationRating` — IN W: Vehicle thermal quality.
        23. `vehicleCargoVolumeM3` — IN W: Vehicle compartment size.
        24. `vehicleReeferKwRated` — IN W: Cooling capacity.
        25. `nodePowerStatus` — IN W: Facility power status (2 levels; 256 outage records).
        26. `siteType` — IN W: Destination type (pharmacy/hospital/clinic).
        27. `siteUrbanRural` — IN W: Destination setting (urban/suburban/rural).
        28. `isHosRegulated` — IN W: Hours-of-service regulation flag.
        29. `loggerMonthsSinceCal` — IN W: Measurement quality control (logger calibration drift).
        
        **EXCLUDED from W:**
        
        - `containerWallAM2` — EXCLUDED AS BUNDLED: stddev=0 within each insulation type. VIP=14.0, PIR=15.0, PUR=15.0, XPS=16.0. Perfectly determined by treatment. Including would absorb treatment effect.
        - `containerDoorAM2` — EXCLUDED AS BUNDLED: stddev=0 within each insulation type. VIP=1.8, PIR=2.0, PUR=2.0, XPS=2.2. Same manufacturing decision as insulation type.
        - `ambientTempAtArrivalC` — EXCLUDED AS COLLINEAR: corr(dispatch, arrival)=0.992. VIF ≈ 64 (>50). Keep ambientTempAtDispatchC (pre-treatment, causally prior).
        - `region` — EXCLUDED (proxy absorption): 7 levels absorbed by nodeId's 28 levels. Kept in query for filter/GRF only.
        - `hasRedundancy` — EXCLUDED (table-grain, absorbed by nodeId): Node-constant attribute. With nodeId in W, perfectly collinear.
        - `refrigSystemType` — EXCLUDED (table-grain, absorbed by nodeId): Node-constant attribute. With nodeId in W, perfectly collinear.
        
        ### mediators_excluded
        None. The hypothesis confirms no directed path from containerInsulationType to excursionFlag through any intermediate variable. corr(containerInsulationType, preDepartureTempC)=0.016, confirming insulation does NOT operate through pre-departure temperature. preDepartureTempC is a confounder, not a mediator.
        
        ---
        
        ### estimation_variants
        
        **Variant 1: primary_categorical_linear**
        - id: `primary_categorical_linear`
        - treatment_column: `containerInsulationType`
        - treatment_form: `CATEGORICAL`
        - model_type: `LinearDML`
        - w_columns: [all 29 W variables listed above]
        - reference_category: `VIP_panel` — lowest excursion rate (3.25% in temperate zones)
        - filter: `{ column: \\"region\\", operator: \\"IN\\", values: [\\"Northeast_NJ\\", \\"Midwest_IN\\", \\"PacificNW_OR\\", \\"Mountain_CO\\"] }`
        - notes: Primary estimate. Scoped to temperate/cool zones where VIP advantage is consistent. Expected n=299,781. Excursion rate in scope = 4.2%.
        
        **Variant 2: primary_categorical_nonparam**
        - id: `primary_categorical_nonparam`
        - treatment_column: `containerInsulationType`
        - treatment_form: `CATEGORICAL`
        - model_type: `NonParamDML`
        - w_columns: [all 29 W variables]
        - reference_category: `VIP_panel`
        - filter: `{ column: \\"region\\", operator: \\"IN\\", values: [\\"Northeast_NJ\\", \\"Midwest_IN\\", \\"PacificNW_OR\\", \\"Mountain_CO\\"] }`
        - notes: Functional form robustness check against primary LinearDML.
        
        **Variant 3: full_sample_categorical_linear**
        - id: `full_sample_categorical_linear`
        - treatment_column: `containerInsulationType`
        - treatment_form: `CATEGORICAL`
        - model_type: `LinearDML`
        - w_columns: [all 29 W variables]
        - reference_category: `VIP_panel`
        - filter: null
        - notes: Full-sample estimate (all 7 regions, n=563,028). Tests whether including hot zones where VIP reverses attenuates or flips the overall estimate.
        
        **Variant 4: hot_zone_categorical_linear**
        - id: `hot_zone_categorical_linear`
        - treatment_column: `containerInsulationType`
        - treatment_form: `CATEGORICAL`
        - model_type: `LinearDML`
        - w_columns: [all 29 W variables]
        - reference_category: `VIP_panel`
        - filter: `{ column: \\"region\\", operator: \\"IN\\", values: [\\"Southwest_AZ\\", \\"SouthCentral_TX\\", \\"Southeast_GA\\"] }`
        - notes: Hot-zone subpopulation. Generator reports VIP reverses to worst in Southwest_AZ (17.08%). Tests reversal explicitly. Expected n=263,247.
        
        **Variant 5: binary_pir_vs_vip_linear**
        - id: `binary_pir_vs_vip_linear`
        - treatment_column: `containerInsulationType`
        - treatment_form: `BINARY_THRESHOLD`
        - model_type: `LinearDML`
        - w_columns: [all 29 W variables]
        - threshold_value: PIR_foam=1, VIP_panel=0
        - filter: `{ column: \\"region\\", operator: \\"IN\\", values: [\\"Northeast_NJ\\", \\"Midwest_IN\\", \\"PacificNW_OR\\", \\"Mountain_CO\\"] }`
        - notes: Pairwise binary contrast. PIR (worst, 4.68%) vs VIP (best, 3.25%) in temperate zones. Data restricted to these two levels. Expected n ≈ 133,862 (88,905 VIP + 44,957 PIR).
        
        **Variant 6: binary_pir_vs_vip_nonparam**
        - id: `binary_pir_vs_vip_nonparam`
        - treatment_column: `containerInsulationType`
        - treatment_form: `BINARY_THRESHOLD`
        - model_type: `NonParamDML`
        - w_columns: [all 29 W variables]
        - threshold_value: PIR_foam=1, VIP_panel=0
        - filter: `{ column: \\"region\\", operator: \\"IN\\", values: [\\"Northeast_NJ\\", \\"Midwest_IN\\", \\"PacificNW_OR\\", \\"Mountain_CO\\"] }`
        - notes: NonParamDML counterpart for PIR vs VIP binary contrast.
        
        ---
        
        ### gates
        
        **nuisance_r2:**
        - outcome_abort: **0.005** — With corr(preDep, excursion)=0.625, achievable R² ≈ 0.39 from preDepartureTempC alone. Adding 28 other confounders should push R² well above 0.005. This threshold detects pipeline failure.
        - outcome_flag: **0.05** — For binary outcome with p=0.042 (temperate zones), theoretical max R²=4×0.042×0.958=0.161. Flag at ~1/3 of theoretical max to catch weak nuisance models.
        - treatment_abort: **0.04** — Structural max for 4-level categorical (~30/24/31/15% split) ≈ 0.75. Abort at 5% of 0.75 = 0.0375, rounded to 0.04.
        - treatment_flag: **0.15** — 20% of structural max = 0.15. Below this suggests confounders don't predict treatment assignment well, which is acceptable (near-exogeneity) but worth investigating.
        - treatment_structural_max_r2: **0.75** — 1 - 1/4 for approximately balanced 4-level categorical.
        
        **sanity:**
        - expected_direction: **+1** — Non-VIP levels should have HIGHER excursion rates relative to VIP_panel reference in temperate zones.
        - abort_magnitude: **0.05** (5pp per category) — 3× the expected within-node effect (~1.5pp marginal, ~0.45pp within-node). 5pp exceeds any plausible single-variable cold chain intervention.
        - flag_magnitude: **0.025** (2.5pp per category) — ~1.5× the marginal spread of 1.43pp between VIP and PIR in temperate zones.
        
        **placebo:**
        - flag_ratio: **0.50** — Real effect expected ~0.5-1.5pp. Flag if placebo ATE > 50% of real ATE. This is moderately tight given the small expected effect size.
        
        ---
        
        ### mediation
        **null** — No mediator identified. The containerInsulationType → excursionFlag path is entirely direct. corr(treatment, preDepartureTempC)=0.016 confirms no mediation through pre-departure temp.
        
        ---
        
        ### grf_configs
        
        **Config 1: primary_region**
        - id: `grf_primary_region`
        - modifier_columns: [region, dispatchMonth]
        - slicing: { region: unique, dispatchMonth: unique }
        - notes: Primary heterogeneity analysis. Region is the main effect modifier per hypothesis. 7 regions × 12 months tests the climate interaction.
        
        **Config 2: climate_continuous**
        - id: `grf_climate_continuous`
        - modifier_columns: [ambientTempAtDispatchC, routeTotalDriveHours, stopSequence]
        - slicing: { ambientTempAtDispatchC: quartile, routeTotalDriveHours: quartile, stopSequence: quartile }
        - notes: Continuous heterogeneity. Tests whether insulation effect varies with ambient temperature (expected threshold ~25°C for VIP reversal), route duration, and stop position.
        
        **Config 3: equipment_interaction**
        - id: `grf_equipment_interaction`
        - modifier_columns: [vehicleRefrigModel, vehicleInsulationRating, nodeRefrigHealthPct]
        - slicing: { vehicleRefrigModel: unique, vehicleInsulationRating: quartile, nodeRefrigHealthPct: quartile }
        - notes: Tests interaction between container insulation and vehicle/facility equipment quality.
        
        ---
        
        ### refutations
        
        1. `{ type: PLACEBO }` — Permuted treatment column
        2. `{ type: RANDOM_CAUSE }` — Random confounder addition
        3. `{ type: SUBSET }` — Data subset stability (random 80% subsamples)
        4. `{ type: TEMPORAL_PLACEBO }` — Lag treatment by one year to test spurious temporal correlation
        
        ---
        
        ### sensitivity
        
        **confounder_drops:**
        (Each W variable, one at a time. deviation_threshold_pct = 20% for all — a >20% change in ATE from dropping a single confounder signals that variable is driving the estimate.)
        
        1. { column: preDepartureTempC, deviation_threshold_pct: 20 }
        2. { column: ambientTempAtDispatchC, deviation_threshold_pct: 20 }
        3. { column: nodeId, deviation_threshold_pct: 20 }
        4. { column: vehicleMakeModel, deviation_threshold_pct: 20 }
        5. { column: vehicleRefrigModel, deviation_threshold_pct: 20 }
        6. { column: routeTotalStops, deviation_threshold_pct: 20 }
        7. { column: routeTotalDriveHours, deviation_threshold_pct: 20 }
        8. { column: routeTotalAirMiles, deviation_threshold_pct: 20 }
        9. { column: stopSequence, deviation_threshold_pct: 20 }
        10. { column: receivingDelayMin, deviation_threshold_pct: 20 }
        11. { column: isAfterHoursArrival, deviation_threshold_pct: 20 }
        12. { column: receivingDockTempControlled, deviation_threshold_pct: 20 }
        13. { column: dayOfWeek, deviation_threshold_pct: 20 }
        14. { column: dispatchMonth, deviation_threshold_pct: 20 }
        15. { column: dispatchYear, deviation_threshold_pct: 20 }
        16. { column: nodeRefrigHealthPct, deviation_threshold_pct: 20 }
        17. { column: productClass, deviation_threshold_pct: 20 }
        18. { column: palletPosition, deviation_threshold_pct: 20 }
        19. { column: productMassAtStopKg, deviation_threshold_pct: 20 }
        20. { column: containerAgeMonths, deviation_threshold_pct: 20 }
        21. { column: vehicleRefrigAgeMonths, deviation_threshold_pct: 20 }
        22. { column: vehicleInsulationRating, deviation_threshold_pct: 20 }
        23. { column: vehicleCargoVolumeM3, deviation_threshold_pct: 20 }
        24. { column: vehicleReeferKwRated, deviation_threshold_pct: 20 }
        25. { column: nodePowerStatus, deviation_threshold_pct: 20 }
        26. { column: siteType, deviation_threshold_pct: 20 }
        27. { column: siteUrbanRural, deviation_threshold_pct: 20 }
        28. { column: isHosRegulated, deviation_threshold_pct: 20 }
        29. { column: loggerMonthsSinceCal, deviation_threshold_pct: 20 }
        
        **confounder_adds:**
        1. { column: ambientTempAtArrivalC, reasoning: \\"Excluded from primary W due to collinearity with ambientTempAtDispatchC (corr=0.992). Adding tests whether arrival-specific ambient exposure captures residual confounding beyond dispatch ambient.\\" }
        2. { column: hasRedundancy, reasoning: \\"Excluded from primary W as absorbed by nodeId (node-constant). Adding tests whether node-level redundancy has residual effect beyond nodeId fixed effect.\\" }
        3. { column: refrigSystemType, reasoning: \\"Excluded from primary W as absorbed by nodeId. Adding tests node-level facility equipment confounding residual.\\" }
        4. { column: containerWallAM2, reasoning: \\"Excluded as bundled with treatment (stddev=0 within type). Adding tests whether controlling for the physical dimension separately changes the estimate (should absorb treatment effect if bundle mechanism is the causal path).\\" }
        
        **threshold_variants:**
        Not applicable — treatment is categorical, no threshold to vary.
        
        **model_variants:**
        1. { primary_variant_id: primary_categorical_linear, alternative_model_type: NonParamDML } — corresponds to variant 2
        2. { primary_variant_id: binary_pir_vs_vip_linear, alternative_model_type: NonParamDML } — corresponds to variant 6
        
        ---
        
        ### structural_breaks
        
        **Config 1: monthly_sensitive**
        - id: `breaks_monthly_sensitive`
        - entity_column: `nodeId`
        - temporal_column: `date`
        - temporal_grain: `MONTH`
        - pelt_penalty: **4.79** — ln(120) = 4.79. Sensitive variant detects smaller regime changes.
        - min_obs_per_period: 50 — 28 nodes × 120 months. Median node has ~20,100 shipments total = ~168/month. Minimum node (NOD_007) has ~12,000 total = ~100/month. 50 is conservative floor.
        - known_events_tables: [hub_interventions, fleet_transitions]
        - entity_count: 28
        - temporal_points: 120
        
        **Config 2: monthly_conservative**
        - id: `breaks_monthly_conservative`
        - entity_column: `nodeId`
        - temporal_column: `date`
        - temporal_grain: `MONTH`
        - pelt_penalty: **14.37** — 3 × ln(120) = 14.37. Conservative variant reduces false positives.
        - min_obs_per_period: 50
        - known_events_tables: [hub_interventions, fleet_transitions]
        - entity_count: 28
        - temporal_points: 120
        
        ---
        
        ### residual_checks
        
        **autocorrelation:**
        1. { temporal_column: date, grain: MONTH, lags: [1, 2, 3, 6, 12], threshold: 0.05 }
           — Monthly autocorrelation captures seasonal residual patterns. Lag 12 checks yearly cycle. Threshold 0.05 flags autocorrelation above noise.
        
        **field_correlation:**
        - threshold: **0.03** — With n=299,781 (temperate scope), spurious correlations are ~1/√n ≈ 0.002. Threshold at 15× noise floor catches meaningful residual correlations.
        - check_columns: [ambientTempAtArrivalC, containerWallAM2, containerDoorAM2, hasRedundancy, refrigSystemType, region, loggerId, preDepartureTempC, ambientTempAtDispatchC, nodeId, vehicleMakeModel, vehicleRefrigModel, routeTotalStops, routeTotalDriveHours, stopSequence, receivingDelayMin, isAfterHoursArrival, dispatchMonth, dispatchYear, nodeRefrigHealthPct, productClass, palletPosition, containerAgeMonths, vehicleRefrigAgeMonths, vehicleInsulationRating]
          — Includes non-W columns (ambientTempAtArrivalC, containerWallAM2, containerDoorAM2, hasRedundancy, refrigSystemType, region, loggerId) AND W columns (to detect nonlinear confounding missed by linear model).
        
        **auto_correction:**
        - max_iterations: 3
        - stop_criterion_ci_pct: 5.0 — Stop if CI width changes less than 5% between iterations.
        
        **metadata_correlation:**
        1. { column: loggerMonthsSinceCal, threshold: 0.03, alert_type: \\"MEASUREMENT_BIAS\\" }
           — Logger calibration drift may create systematic measurement error in excursionFlag.
        2. { column: loggerId, threshold: 0.03, alert_type: \\"MEASUREMENT_DEVICE_BIAS\\" }
           — Individual logger devices may have systematic bias affecting excursion detection.
        
        ---
        
        ### range_checks
        
        **vif:**
        - threshold: **50**
        - drop_pairs:
          1. { keep: ambientTempAtDispatchC, drop: ambientTempAtArrivalC, reasoning: \\"corr=0.992, VIF≈64. Keep dispatch (pre-treatment, causally prior). Arrival is post-transit and temporally later.\\" }
          — No other pair in W exceeds VIF 50. Next highest: routeTotalStops ↔ routeTotalDriveHours (corr=0.496, VIF≈1.33), preDepartureTempC ↔ ambientTempAtDispatchC (corr=0.684, VIF≈1.88).
        
        **overlap:**
        1. { variant_id: binary_pir_vs_vip_linear, threshold: 0.05, response_strategy: TRIM, trim_bounds: [0.01, 0.99] }
           — Binary PIR vs VIP. Treatment proportion: PIR ~34% (44,957/133,862). Well-balanced, unlikely to violate, but set trim for safety.
        2. { variant_id: binary_pir_vs_vip_nonparam, threshold: 0.05, response_strategy: TRIM, trim_bounds: [0.01, 0.99] }
        
        **variance:**
        1. { column: containerInsulationType, structural_note: \\"4-level categorical. Approximately balanced in temperate scope: VIP=88,905 (29.7%), PUR=72,627 (24.2%), XPS=93,292 (31.1%), PIR=44,957 (15.0%). PIR is the smallest group but well above degeneracy.\\" }
        2. { column: excursionFlag, structural_note: \\"Binary outcome with 4.2% prevalence in temperate scope (~12,590 events). Sufficient events for reliable estimation.\\" }
        3. { column: nodePowerStatus, structural_note: \\"Near-degenerate: 256 outage records out of 563,028 (0.045%). In W as greedy inclusion but contributes minimal confounding information. Tree models will likely ignore it.\\" }
        
        ---
        
        ### unmeasured_confounding
        
        1. { variant_id: primary_categorical_linear, method: E_VALUE, null_hypothesis: \\"ATE = 0\\", notes: \\"How strong would an unmeasured confounder need to associate with both container insulation type and excursion risk to explain the observed ~0.5-1.5pp ATE in temperate zones?\\" }
        2. { variant_id: primary_categorical_linear, method: ROSENBAUM_BOUNDS, null_hypothesis: \\"ATE = 0\\", notes: \\"Critical Γ for the primary categorical estimate. The main concern is unmeasured container handling quality or pre-conditioning protocol that varies by insulation type.\\" }
        3. { variant_id: full_sample_categorical_linear, method: E_VALUE, null_hypothesis: \\"ATE = 0\\", notes: \\"Full-sample E-value. Effect may be smaller due to hot-zone reversal averaging.\\" }
        4. { variant_id: hot_zone_categorical_linear, method: E_VALUE, null_hypothesis: \\"ATE = 0\\", notes: \\"Hot-zone E-value. Tests unmeasured confounding for the reversed VIP effect in hot climates.\\" }
        5. { variant_id: binary_pir_vs_vip_linear, method: E_VALUE, null_hypothesis: \\"ATE = 0\\", notes: \\"Binary contrast E-value for the extreme comparison.\\" }
        6. { variant_id: binary_pir_vs_vip_linear, method: ROSENBAUM_BOUNDS, null_hypothesis: \\"ATE = 0\\", notes: \\"Rosenbaum bounds for the PIR vs VIP binary contrast.\\" }
        
        ---
        
        ### externalization
        
        **domain_rankings:**
        1. {
            domain_ranking: [VIP_panel, PIR_foam, PUR_foam, XPS_foam],
            source: \\"Thermal conductivity values: VIP 3-7 mW/m·K, PIR/PUR ~20-24 mW/m·K, XPS 28-35 mW/m·K. Domain predicts VIP > PIR ≈ PUR > XPS in insulation quality.\\",
            comparison_method: \\"Spearman rank correlation between domain-predicted thermal performance ordering and GRF-derived categorical coefficient ordering (lower excursion rate = better rank)\\",
            scope: \\"Temperate zones only (Northeast_NJ, Midwest_IN, PacificNW_OR, Mountain_CO). Direction reverses in extreme heat.\\",
            expected_concordance: \\"positive — domain ranking should partially align. Generator notes PIR/XPS ordering differs from textbook (PIR worst empirically vs mid-range expected), suggesting real-world degradation patterns differ from laboratory conductivity.\\"
          }
        
        **allocation_bias:**
        1. { treatment_column: containerInsulationType, grouping_column: nodeId, flag_threshold: 0.10 }
           — Generator reports HHI=0.053-0.055 across insulation types. All below 0.10 threshold suggests relatively uniform deployment across 28 nodes, but confirms non-random assignment exists.
        2. { treatment_column: containerInsulationType, grouping_column: region, flag_threshold: 0.10 }
           — Check whether insulation type deployment is concentrated in specific regions (would compound ecological fallacy).
        
        ---
        
        ### discrepancy_log
        
        1. {
            field: \\"wallAM2/doorAM2 as derived features\\",
            generator_value: \\"Included as DERIVED_FEATURES for the query (containerWallAM2, containerDoorAM2)\\",
            compiler_value: \\"Included in query but EXCLUDED from W as BUNDLED (stddev=0 within each insulation type)\\",
            resolution: \\"Variables included in query for residual diagnostics and sensitivity check (confounder_adds) but excluded from primary W. Adding containerWallAM2 to W would absorb treatment effect since it is perfectly determined by containerInsulationType.\\"
          }
        
        ---
        
        ### COMPLIANCE CHECKLIST
        
        | Field | Status |
        |-------|--------|
        | hypothesis_id | ✅ Provided |
        | treatment | ✅ containerInsulationType |
        | outcome | ✅ excursionFlag |
        | treatment_form | ✅ CATEGORICAL |
        | query | ✅ Full QueryDTO with verified join and row count |
        | expected_row_count | ✅ 563,028 verified |
        | strip_columns | ✅ [shipmentId] |
        | dag_edges | ✅ 13 edges with intermediate structure |
        | dsep_threshold | ✅ 0.034 calibrated from 4 independent pairs |
        | adjustment_set | ✅ 29 W variables with full classification rationale |
        | mediators_excluded | ✅ None (explicitly justified) |
        | estimation_variants | ✅ 6 variants (2 categorical primary, 1 full-sample, 1 hot-zone, 2 binary contrasts) |
        | gates.nuisance_r2 | ✅ All 5 sub-fields with derivation |
        | gates.sanity | ✅ Direction +1, abort 5pp, flag 2.5pp |
        | gates.placebo | ✅ flag_ratio 0.50 |
        | mediation | ✅ null (justified) |
        | grf_configs | ✅ 3 configs (region, climate continuous, equipment) |
        | refutations | ✅ 4 types (placebo, random cause, subset, temporal placebo) |
        | sensitivity.confounder_drops | ✅ All 29 W variables at 20% threshold |
        | sensitivity.confounder_adds | ✅ 4 variables with reasoning |
        | sensitivity.threshold_variants | ✅ N/A (categorical) |
        | sensitivity.model_variants | ✅ 2 pairs |
        | structural_breaks | ✅ 2 configs (sensitive + conservative penalties) |
        | residual_checks.autocorrelation | ✅ Monthly with 5 lags |
        | residual_checks.field_correlation | ✅ threshold 0.03, 25+ check columns |
        | residual_checks.auto_correction | ✅ 3 iterations, 5% stop |
        | residual_checks.metadata_correlation | ✅ 2 metadata fields |
        | range_checks.vif | ✅ threshold 50, 1 drop pair documented |
        | range_checks.overlap | ✅ 2 binary variants with TRIM strategy |
        | range_checks.variance | ✅ 3 columns documented |
        | unmeasured_confounding | ✅ 6 entries (E_VALUE + ROSENBAUM for primary & binary) |
        | externalization.domain_rankings | ✅ 1 ranking with scope limitation |
        | externalization.allocation_bias | ✅ 2 checks (nodeId, region) |
        | discrepancy_log | ✅ 1 discrepancy documented |
        """;

    String SAMPLE_COMPILED_H3 = """
        Good. The query produces 563,028 rows with no fan-out, all columns populated. The ambient dispatch/arrival correlation is 0.992 — I'll need to drop one for VIF. Now I have all data needed. Let me compile.
        
        ---
        
        # PIPELINE SPECIFICATION
        
        ## hypothesis_id
        `nodeRefrigHealthPct_excursionFlag`
        
        ## treatment
        `nodeRefrigHealthPct`
        
        ## outcome
        `excursionFlag`
        
        ## treatment_form
        `CONTINUOUS` (primary), with `BINARY_THRESHOLD` at 70 as secondary
        
        ---
        
        ## query
        
        **Reasoning**: Grain is one row per shipment (563,028 rows). Enrichment join to `cold_nodes` on `nodeId` for `hasRedundancy` and `region` (GRF modifiers). Join match rate verified at 100% (563,028/563,028). No fan-out.
        
        ```json
        {
          \\"from\\": \\"shipments\\",
          \\"fromAlias\\": \\"s\\",
          \\"joins\\": [
            {
              \\"joinType\\": \\"LEFT\\",
              \\"joinedRoot\\": {\\"rootName\\": \\"cold_nodes\\", \\"alias\\": \\"cn\\"},
              \\"onCondition\\": {
                \\"@type\\": \\"binary\\",
                \\"left\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.nodeId\\"},
                \\"operator\\": \\"EQUALS\\",
                \\"right\\": {\\"@type\\": \\"path\\", \\"path\\": \\"cn.nodeId\\"}
              }
            }
          ],
          \\"selector\\": {
            \\"@type\\": \\"multi\\",
            \\"expressions\\": [
              {\\"alias\\": \\"shipmentId\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.shipmentId\\"}},
              {\\"alias\\": \\"nodeRefrigHealthPct\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.nodeRefrigHealthPct\\"}},
              {\\"alias\\": \\"excursionFlag\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.excursionFlag\\"}},
              {\\"alias\\": \\"preDepartureTempC\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.preDepartureTempC\\"}},
              {\\"alias\\": \\"nodePowerStatus\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.nodePowerStatus\\"}},
              {\\"alias\\": \\"nodeId\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.nodeId\\"}},
              {\\"alias\\": \\"ambientTempAtDispatchC\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.ambientTempAtDispatchC\\"}},
              {\\"alias\\": \\"ambientTempAtArrivalC\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.ambientTempAtArrivalC\\"}},
              {\\"alias\\": \\"containerInsulationType\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.containerInsulationType\\"}},
              {\\"alias\\": \\"vehicleMakeModel\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.vehicleMakeModel\\"}},
              {\\"alias\\": \\"vehicleRefrigModel\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.vehicleRefrigModel\\"}},
              {\\"alias\\": \\"routeTotalDriveHours\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.routeTotalDriveHours\\"}},
              {\\"alias\\": \\"routeTotalStops\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.routeTotalStops\\"}},
              {\\"alias\\": \\"stopSequence\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.stopSequence\\"}},
              {\\"alias\\": \\"receivingDelayMin\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.receivingDelayMin\\"}},
              {\\"alias\\": \\"isAfterHoursArrival\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.isAfterHoursArrival\\"}},
              {\\"alias\\": \\"receivingDockTempControlled\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.receivingDockTempControlled\\"}},
              {\\"alias\\": \\"dayOfWeek\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.dayOfWeek\\"}},
              {\\"alias\\": \\"dispatchMonth\\", \\"expression\\": {\\"@type\\": \\"function\\", \\"functionName\\": \\"EXTRACT\\", \\"arguments\\": [{\\"@type\\": \\"literal\\", \\"value\\": \\"MONTH\\"}, {\\"@type\\": \\"path\\", \\"path\\": \\"s.date\\"}]}},
              {\\"alias\\": \\"dispatchYear\\", \\"expression\\": {\\"@type\\": \\"function\\", \\"functionName\\": \\"EXTRACT\\", \\"arguments\\": [{\\"@type\\": \\"literal\\", \\"value\\": \\"YEAR\\"}, {\\"@type\\": \\"path\\", \\"path\\": \\"s.date\\"}]}},
              {\\"alias\\": \\"productClass\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.productClass\\"}},
              {\\"alias\\": \\"palletPosition\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.palletPosition\\"}},
              {\\"alias\\": \\"productMassAtStopKg\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.productMassAtStopKg\\"}},
              {\\"alias\\": \\"containerAgeMonths\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.containerAgeMonths\\"}},
              {\\"alias\\": \\"vehicleRefrigAgeMonths\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.vehicleRefrigAgeMonths\\"}},
              {\\"alias\\": \\"hasRedundancy\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"cn.hasRedundancy\\"}},
              {\\"alias\\": \\"region\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"cn.region\\"}},
              {\\"alias\\": \\"refrigSystemType\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"cn.refrigSystemType\\"}},
              {\\"alias\\": \\"loggerMonthsSinceCal\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.loggerMonthsSinceCal\\"}},
              {\\"alias\\": \\"loggerId\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.loggerId\\"}},
              {\\"alias\\": \\"dispatchDate\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.date\\"}},
              {\\"alias\\": \\"dispatchTimestamp\\", \\"expression\\": {\\"@type\\": \\"path\\", \\"path\\": \\"s.dispatchTimestamp\\"}}
            ]
          }
        }
        ```
        
        ## expected_row_count
        563,028 — verified by COUNT query with the same join structure.
        
        ## strip_columns
        `[\\"shipmentId\\", \\"dispatchDate\\", \\"dispatchTimestamp\\", \\"loggerId\\", \\"ambientTempAtArrivalC\\"]`
        
        - `shipmentId`: ID only, no analytical content
        - `dispatchDate` / `dispatchTimestamp`: kept for temporal ordering in structural breaks and autocorrelation only
        - `loggerId`: measurement metadata for correlation checks, stripped from model fitting
        - `ambientTempAtArrivalC`: EXCLUDED from analysis. corr=0.992 with ambientTempAtDispatchC (VIF>50). Keep dispatch ambient, drop arrival ambient. Dispatch ambient is more relevant to the facility-level treatment mechanism.
        
        ---
        
        ## dag_edges
        
        ```
        nodeRefrigHealthPct -> excursionFlag;         # direct: facility degradation raises transit excursion risk
        nodeRefrigHealthPct -> preDepartureTempC;      # mechanism: degraded facility refrig → product starts warmer
        preDepartureTempC -> excursionFlag;            # mechanism: less thermal buffer → more excursion risk
        nodePowerStatus -> nodeRefrigHealthPct;        # mechanism: outage → refrig reads 0-10%
        ambientTempAtDispatchC -> nodeRefrigHealthPct; # mechanism: heat stress on facility compressor
        ambientTempAtDispatchC -> preDepartureTempC;   # mechanism: external heat drives facility internal temp
        ambientTempAtDispatchC -> excursionFlag;       # mechanism: external heat drives transit heat load
        nodeId -> nodeRefrigHealthPct;                 # mechanism: node-specific refrig system age/type
        nodeId -> ambientTempAtDispatchC;              # mechanism: node location determines climate
        dispatchMonth -> ambientTempAtDispatchC;       # mechanism: seasonality drives ambient temperature
        dispatchYear -> vehicleMakeModel;             # mechanism: fleet modernization replaces models over time
        stopSequence -> excursionFlag;                 # mechanism: cumulative door openings → heat ingress
        containerInsulationType -> excursionFlag;      # mechanism: transit insulation quality
        vehicleMakeModel -> excursionFlag;            # mechanism: vehicle thermal envelope quality
        vehicleRefrigModel -> excursionFlag;          # mechanism: vehicle reefer efficiency
        productMassAtStopKg -> excursionFlag;         # mechanism: thermal mass buffer reduces excursion risk
        receivingDelayMin -> excursionFlag;           # mechanism: post-transit uncontrolled exposure
        routeTotalDriveHours -> excursionFlag;        # mechanism: transit duration → more heat accumulation
        ```
        
        Testable d-sep implications include:
        - nodePowerStatus ⊥ excursionFlag | {nodeRefrigHealthPct, preDepartureTempC, ambientTempAtDispatchC} (outage affects outcome only through health and preDep)
        - stopSequence ⊥ nodeRefrigHealthPct | nodeId (delivery position unrelated to facility health given the node)
        - containerInsulationType ⊥ nodeRefrigHealthPct | nodeId (container type unrelated to facility health given the node)
        
        ---
        
        ## dsep_threshold
        
        **Calibration**: Computed pairwise correlations among genuinely independent pairs (unrelated measurement domains, no shared upstream cause):
        
        | Pair | Correlation | Domain relationship |
        |------|-------------|-------------------|
        | palletPosition ↔ nodeRefrigHealthPct | 0.010 | Loading practice vs facility equipment — independent |
        | containerAgeMonths ↔ stopSequence | 0.017 | Container lifecycle vs route delivery order — independent |
        | loggerMonthsSinceCal ↔ palletPosition | 0.0002 | Measurement calibration vs loading position — independent |
        | containerAgeMonths ↔ loggerMonthsSinceCal | 0.017 | Container lifecycle vs logger calibration — independent |
        | vehicleCargoVolumeM3 ↔ receivingDelayMin | 0.022 | Vehicle capacity vs site receiving process — independent |
        
        Max noise-floor correlation: 0.022
        Threshold: 2.0 × 0.022 = **0.044**
        
        ---
        
        ## adjustment_set
        
        **W matrix** (19 variables):
        
        | Variable | Classification | Reasoning |
        |----------|---------------|-----------|
        | nodeId | IN W | 28 levels. Absorbs all node-constant confounders (region, hasRedundancy, refrigSystemType, etc.). Essential for within-node estimation and Simpson's Paradox control. |
        | ambientTempAtDispatchC | IN W | Confounds both treatment (heat stress → refrig degradation, corr=-0.0004 marginal but causal) and outcome (corr=0.342). Exogenous climate variable. |
        | containerInsulationType | IN W | 4 levels. Affects transit thermal performance → outcome. Independent of facility refrig health. |
        | vehicleMakeModel | IN W | 9 levels. Vehicle thermal envelope quality → outcome. Saturated in SS Run 4. |
        | vehicleRefrigModel | IN W | 9 levels. Reefer efficiency → outcome. Saturated in SS Run 4. |
        | routeTotalDriveHours | IN W | Transit duration → heat accumulation → outcome. |
        | routeTotalStops | IN W | Route complexity, corr=0.496 with routeTotalDriveHours but below VIF threshold. |
        | stopSequence | IN W | Delivery position, corr=0.240 with outcome (strong). |
        | receivingDelayMin | IN W | Post-transit exposure. |
        | isAfterHoursArrival | IN W | After-hours → longer uncontrolled exposure. |
        | receivingDockTempControlled | IN W | Dock temperature control at destination. |
        | dayOfWeek | IN W | 6 levels. Operational patterns. |
        | dispatchMonth | IN W | 12 levels. Seasonality — excursion rate ranges from 0.1% (January) to 25.7% (July). Critical confounder. |
        | dispatchYear | IN W | 10 levels (2015-2024). Secular trends: fleet modernization, protocol changes. |
        | productClass | IN W | 2 levels (A/B). Product sensitivity affects measured excursion risk. |
        | palletPosition | IN W | Loading position → airflow → temperature. |
        | productMassAtStopKg | IN W | Thermal mass buffer, corr=-0.227 with outcome. |
        | containerAgeMonths | IN W | Container condition (confirmed null but harmless inclusion). |
        | vehicleRefrigAgeMonths | IN W | Vehicle refrig age (confirmed null within-model but harmless inclusion). |
        
        **Variables explicitly excluded:**
        
        | Variable | Classification | Reasoning |
        |----------|---------------|-----------|
        | preDepartureTempC | EXCLUDED AS MEDIATOR | On directed path: nodeRefrigHealthPct → preDepartureTempC → excursionFlag. Including blocks indirect effect. Direct-effect variants with preDep in W are specified. |
        | ambientTempAtArrivalC | EXCLUDED AS COLLINEAR | corr=0.992 with ambientTempAtDispatchC; VIF=62.5 (>50). Keep dispatch ambient (relevant to facility-level treatment mechanism). |
        | nodePowerStatus | EXCLUDED AS TREATMENT MECHANISM | Power outage is a strict subset of severe degradation (all 256 outage records have health <50%). It's part of the treatment mechanism — including in W would absorb treatment variation. Include as GRF modifier only. |
        | hasRedundancy | ABSORBED BY nodeId | Node-constant (28 nodes × 1 value each). nodeId absorbs all node-constant variation. Include as GRF modifier for interpretability. |
        | region | ABSORBED BY nodeId | Node-constant (each node belongs to exactly one region). nodeId absorbs it. Include as GRF modifier. |
        | refrigSystemType | ABSORBED BY nodeId | Node-constant. Each node has one refrig system type. |
        
        ---
        
        ## mediators_excluded
        
        ```
        [
          {
            \\"column\\": \\"preDepartureTempC\\",
            \\"pathway\\": \\"nodeRefrigHealthPct → preDepartureTempC → excursionFlag (facility health degradation raises departure temperature, reducing thermal buffer)\\",
            \\"direct_effect_variant_id\\": \\"v_cont_direct_linear\\"
          }
        ]
        ```
        
        ---
        
        ## estimation_variants
        
        **Variant 1: v_cont_linear** (PRIMARY)
        - id: `v_cont_linear`
        - treatment_column: `nodeRefrigHealthPct`
        - treatment_form: CONTINUOUS
        - model_type: LinearDML
        - w_columns: [nodeId, ambientTempAtDispatchC, containerInsulationType, vehicleMakeModel, vehicleRefrigModel, routeTotalDriveHours, routeTotalStops, stopSequence, receivingDelayMin, isAfterHoursArrival, receivingDockTempControlled, dayOfWeek, dispatchMonth, dispatchYear, productClass, palletPosition, productMassAtStopKg, containerAgeMonths, vehicleRefrigAgeMonths]
        - notes: Primary total-effect estimate. Continuous treatment captures the full dose-response gradient.
        
        **Variant 2: v_cont_nonparam** (FUNCTIONAL FORM CHECK)
        - id: `v_cont_nonparam`
        - treatment_column: `nodeRefrigHealthPct`
        - treatment_form: CONTINUOUS
        - model_type: NonParamDML
        - w_columns: same as v_cont_linear
        - notes: Functional form check for v_cont_linear. Tree-based nuisance models may better capture the extreme nonlinearity.
        
        **Variant 3: v_binary70_linear** (THRESHOLD)
        - id: `v_binary70_linear`
        - treatment_column: `nodeRefrigHealthPct`
        - treatment_form: BINARY_THRESHOLD
        - threshold_value: 70
        - model_type: LinearDML
        - w_columns: same as v_cont_linear
        - notes: Binary treatment (health<70 vs ≥70). 3,889 treated vs 559,139 control. SEVERE positivity concern (ratio 1:144). Must use TRIM. Threshold validated by inflection analysis showing step-change from 8.2% at 72% health to 28.8% at 67% health.
        
        **Variant 4: v_binary70_nonparam** (THRESHOLD + FUNCTIONAL FORM CHECK)
        - id: `v_binary70_nonparam`
        - treatment_column: `nodeRefrigHealthPct`
        - treatment_form: BINARY_THRESHOLD
        - threshold_value: 70
        - model_type: NonParamDML
        - w_columns: same as v_cont_linear
        - notes: NonParamDML counterpart of v_binary70_linear.
        
        **Variant 5: v_cont_direct_linear** (DIRECT EFFECT)
        - id: `v_cont_direct_linear`
        - treatment_column: `nodeRefrigHealthPct`
        - treatment_form: CONTINUOUS
        - model_type: LinearDML
        - w_columns: [nodeId, ambientTempAtDispatchC, containerInsulationType, vehicleMakeModel, vehicleRefrigModel, routeTotalDriveHours, routeTotalStops, stopSequence, receivingDelayMin, isAfterHoursArrival, receivingDockTempControlled, dayOfWeek, dispatchMonth, dispatchYear, productClass, palletPosition, productMassAtStopKg, containerAgeMonths, vehicleRefrigAgeMonths, **preDepartureTempC**]
        - notes: Direct effect variant. Adding mediator preDepartureTempC to W blocks the indirect path, isolating the direct effect of facility health on excursion that does NOT operate through departure temperature.
        
        **Variant 6: v_cont_direct_nonparam** (DIRECT EFFECT + FUNCTIONAL FORM CHECK)
        - id: `v_cont_direct_nonparam`
        - treatment_column: `nodeRefrigHealthPct`
        - treatment_form: CONTINUOUS
        - model_type: NonParamDML
        - w_columns: same as v_cont_direct_linear (including preDepartureTempC)
        - notes: NonParamDML counterpart of v_cont_direct_linear.
        
        ---
        
        ## gates
        
        ### nuisance_r2
        
        - **outcome_abort**: 0.005
          - Justification: Strongest single W predictor is ambientTempAtDispatchC (corr=0.342, R²=0.117). With ~19 predictors including high-signal stopSequence (corr=0.240) and productMassAtStopKg (corr=-0.227), achievable R² ≈ 0.15-0.25. Abort at 0.005 indicates total pipeline failure.
        - **outcome_flag**: 0.04
          - Justification: ~25% of achievable R² lower bound (0.15).
        - **treatment_abort**: 0.001
          - Justification: Treatment model structural max R² estimated ~0.05-0.10 (28 node-level groups with sporadic degradation events). Correlations of all W variables with treatment are near-zero (max 0.031 for routeTotalStops). Treatment is near-exogenous (equipment failures are random). Abort at 0.001 = ~1-2% of structural max.
        - **treatment_flag**: 0.005
          - Justification: ~5-10% of structural max.
        - **treatment_structural_max_r2**: 0.10
          - Justification: Continuous treatment with mean=99.5, stddev=5.5. Variance is 30.25. Between-node variation in health accounts for at most ~10% of total variance (most variance is within-node random degradation events).
        
        ### sanity
        
        - **expected_direction**: -1 (higher nodeRefrigHealthPct → lower excursionFlag)
        - **abort_magnitude**: 0.03
          - Justification: Per percentage-point of health. A 3pp excursion increase per 1pp health decrease would imply 30pp increase over a 10pp health decline — implausible given the flat 90-100% range dominates the data.
        - **flag_magnitude**: 0.01
          - Justification: Per percentage-point. Given that 99% of observations are in the flat 90-100% range, the population-average marginal effect should be small. A 1pp excursion change per 1pp health change seems implausibly large for a population-average effect.
        
        ### placebo
        
        - **flag_ratio**: 0.15
          - Justification: Real effect likely small (dominated by the 99% flat-range observations). Even small placebo leakage would constitute a large fraction. Use tighter 15% ratio. At n=563K, statistical significance is trivially achieved; magnitude comparison is critical.
        
        ---
        
        ## mediation
        
        ```
        [
          {
            \\"mediator\\": \\"preDepartureTempC\\",
            \\"pathway\\": \\"nodeRefrigHealthPct → preDepartureTempC → excursionFlag\\",
            \\"total_variant_id\\": \\"v_cont_linear\\",
            \\"direct_variant_id\\": \\"v_cont_direct_linear\\"
          }
        ]
        ```
        
        Indirect effect = total (v_cont_linear) - direct (v_cont_direct_linear). The generator notes that facility health degradation raises departure temperature (corr(nodeRefrigHealthPct, preDepartureTempC) = -0.136), which in turn drives excursion risk (corr(preDepartureTempC, excursionFlag) = 0.625).
        
        ---
        
        ## grf_configs
        
        **Config 1: grf_primary** (Hypothesis-driven modifiers)
        - id: `grf_primary`
        - modifier_columns: [nodePowerStatus, ambientTempAtDispatchC, hasRedundancy, region]
        - slicing:
          - nodePowerStatus: unique (2 levels: normal, outage)
          - ambientTempAtDispatchC: quartile
          - hasRedundancy: unique (2 levels: true, false)
          - region: unique (7 levels)
        - Justification: nodePowerStatus is the primary effect modifier (outage amplifies degradation effect from 54.1% to 79.3% excursion at health<50%). hasRedundancy interacts with outage (provides ~8pp protection). region and ambient temp capture climate-driven heterogeneity.
        
        **Config 2: grf_secondary** (Extended exploration)
        - id: `grf_secondary`
        - modifier_columns: [containerInsulationType, vehicleRefrigModel, stopSequence, dispatchMonth, refrigSystemType]
        - slicing:
          - containerInsulationType: unique (4 levels)
          - vehicleRefrigModel: unique (9 levels)
          - stopSequence: quartile
          - dispatchMonth: unique (12 levels)
          - refrigSystemType: unique (3 levels: Carrier_Aquaforce, Bitzer_CSVH, Danfoss_Turbocor)
        - Justification: Explores whether facility health degradation effect varies by transit equipment, delivery position, season, or facility refrig system type.
        
        ---
        
        ## refutations
        
        ```
        [
          {\\"type\\": \\"PLACEBO\\"},
          {\\"type\\": \\"RANDOM_CAUSE\\"},
          {\\"type\\": \\"SUBSET\\"}
        ]
        ```
        
        No TEMPORAL_PLACEBO because the treatment (nodeRefrigHealthPct) is a point-in-time facility condition, not a temporal intervention with a before/after structure.
        
        ---
        
        ## sensitivity
        
        ### confounder_drops
        
        One per W variable. Deviation threshold 25% for most variables, tighter (15%) for the two strongest confounders:
        
        | column | deviation_threshold_pct |
        |--------|------------------------|
        | nodeId | 15 |
        | ambientTempAtDispatchC | 15 |
        | containerInsulationType | 25 |
        | vehicleMakeModel | 25 |
        | vehicleRefrigModel | 25 |
        | routeTotalDriveHours | 25 |
        | routeTotalStops | 25 |
        | stopSequence | 25 |
        | receivingDelayMin | 25 |
        | isAfterHoursArrival | 25 |
        | receivingDockTempControlled | 25 |
        | dayOfWeek | 25 |
        | dispatchMonth | 15 |
        | dispatchYear | 15 |
        | productClass | 25 |
        | palletPosition | 25 |
        | productMassAtStopKg | 25 |
        | containerAgeMonths | 25 |
        | vehicleRefrigAgeMonths | 25 |
        
        nodeId at 15%: absorbs all node-level confounders; dropping it exposes the estimate to ecological fallacy (Simpson's Paradox confirmed).
        ambientTempAtDispatchC at 15%: strongest outcome predictor in W (corr=0.342) and the primary exogenous confounder.
        dispatchMonth at 15%: excursion rate varies 0.1-25.7% by month; dropping it creates massive seasonal confounding.
        dispatchYear at 15%: 10-year span with fleet modernization; dropping exposes secular trends.
        
        ### confounder_adds
        
        | column | reasoning |
        |--------|-----------|
        | nodePowerStatus | Excluded from W as treatment mechanism. If adding it changes estimate, it captures confounding not mediated through nodeRefrigHealthPct (e.g., operational chaos during outage). |
        | preDepartureTempC | Excluded as mediator. Adding tests whether blocking the mediator changes inference materially (provides direct effect comparison). |
        | ambientTempAtArrivalC | Excluded for collinearity with ambientTempAtDispatchC. If adding changes estimate beyond collinearity noise, arrival conditions provide independent confounding information. |
        | siteUrbanRural | Not in primary W. If destination setting affects both facility routing (and thus node selection → refrig health) and outcome, omission biases. |
        | routeTotalAirMiles | Not in primary W (redundant with routeTotalDriveHours). Adding tests whether air miles provide independent confounding information. |
        
        ### threshold_variants
        
        | threshold | expected_n_treated | expected_n_control |
        |-----------|-------------------|-------------------|
        | 50 | 2,572 | 560,456 |
        | 60 | 3,189 | 559,839 |
        | 70 | 3,889 | 559,139 |
        | 80 | 4,411 | 558,617 |
        | 90 | 4,952 | 558,076 |
        
        The inflection analysis identifies 70 as the primary breakpoint. 50 and 60 test sensitivity to stricter thresholds (higher severity). 80 and 90 test sensitivity to more liberal thresholds. All have severe positivity issues (treated <1%).
        
        ### model_variants
        
        | primary_variant_id | alternative_model_type |
        |-------------------|----------------------|
        | v_cont_linear | NonParamDML |
        | v_binary70_linear | NonParamDML |
        | v_cont_direct_linear | NonParamDML |
        
        ---
        
        ## structural_breaks
        
        **Config 1: breaks_monthly_sensitive**
        - id: `breaks_monthly_sensitive`
        - entity_column: `nodeId`
        - temporal_column: `dispatchDate`
        - temporal_grain: month
        - pelt_penalty: 4.79 — sensitive variant. Derived: log(120) = 4.79, where T=120 months (Jan 2015 - Dec 2024).
        - min_obs_per_period: 100 — average 563,028 / (28 nodes × 120 months) ≈ 168 obs per node-month. Min 100 captures most cells.
        - known_events_tables: [hub_interventions, fleet_transitions]
        - entity_count: 28
        - temporal_points: 120
        
        **Config 2: breaks_monthly_conservative**
        - id: `breaks_monthly_conservative`
        - entity_column: `nodeId`
        - temporal_column: `dispatchDate`
        - temporal_grain: month
        - pelt_penalty: 14.37 — conservative variant. Derived: 3 × log(120) = 14.37.
        - min_obs_per_period: 100
        - known_events_tables: [hub_interventions, fleet_transitions]
        - entity_count: 28
        - temporal_points: 120
        
        **Config 3: breaks_quarterly_sensitive**
        - id: `breaks_quarterly_sensitive`
        - entity_column: `nodeId`
        - temporal_column: `dispatchDate`
        - temporal_grain: quarter
        - pelt_penalty: 3.69 — sensitive variant. Derived: log(40) = 3.69, where T=40 quarters.
        - min_obs_per_period: 300
        - known_events_tables: [hub_interventions, fleet_transitions]
        - entity_count: 28
        - temporal_points: 40
        
        ---
        
        ## residual_checks
        
        ### autocorrelation
        
        ```
        [
          {
            \\"temporal_column\\": \\"dispatchDate\\",
            \\"grain\\": \\"month\\",
            \\"lags\\": [1, 3, 6, 12],
            \\"threshold\\": 0.03
          },
          {
            \\"temporal_column\\": \\"dispatchDate\\",
            \\"grain\\": \\"week\\",
            \\"lags\\": [1, 2, 4],
            \\"threshold\\": 0.03
          }
        ]
        ```
        
        Threshold 0.03: At n=563K, even tiny autocorrelation is significant. With ~120 monthly cells, lag-1 autocorrelation > 0.03 suggests meaningful temporal structure not captured by dispatchMonth/dispatchYear.
        
        ### field_correlation
        
        - threshold: 0.02
          - Justification: At n=563K, even small correlations are statistically significant. Threshold should be above noise floor (0.022 max among independent pairs). Set at 0.02 to catch meaningful residual correlation. Actually given noise floor at 0.022, set at **0.025** — slightly above noise floor to avoid false positives.
        - threshold: **0.025**
        - check_columns: [preDepartureTempC, nodePowerStatus, hasRedundancy, region, refrigSystemType, loggerMonthsSinceCal, ambientTempAtArrivalC, routeTotalAirMiles, siteUrbanRural, nodeId, ambientTempAtDispatchC, containerInsulationType, vehicleMakeModel, vehicleRefrigModel, routeTotalDriveHours, routeTotalStops, stopSequence, receivingDelayMin, isAfterHoursArrival, receivingDockTempControlled, dayOfWeek, dispatchMonth, dispatchYear, productClass, palletPosition, productMassAtStopKg, containerAgeMonths, vehicleRefrigAgeMonths]
        
        ### auto_correction
        
        - max_iterations: 3
        - stop_criterion_ci_pct: 5.0
          - Justification: Stop when adding a flagged residual-correlated variable to W changes the CI width by less than 5%.
        
        ### metadata_correlation
        
        ```
        [
          {
            \\"column\\": \\"loggerMonthsSinceCal\\",
            \\"threshold\\": 0.02,
            \\"alert_type\\": \\"MEASUREMENT_BIAS\\"
          },
          {
            \\"column\\": \\"loggerId\\",
            \\"threshold\\": 0.02,
            \\"alert_type\\": \\"MEASUREMENT_BIAS\\"
          }
        ]
        ```
        
        Logger calibration drift and device identity could create systematic measurement bias in excursionFlag. If DML residuals correlate with these, the causal estimate may reflect logger bias rather than real treatment effect.
        
        ---
        
        ## range_checks
        
        ### vif
        
        - threshold: 50
        - drop_pairs:
          ```
          [
            {
              \\"keep\\": \\"ambientTempAtDispatchC\\",
              \\"drop\\": \\"ambientTempAtArrivalC\\",
              \\"reasoning\\": \\"corr=0.992, VIF=62.5. Keep dispatch ambient because the treatment (facility refrig health) operates at the dispatch facility. Arrival ambient is redundant for this hypothesis.\\"
            }
          ]
          ```
          \n  Note: ambientTempAtArrivalC is already in strip_columns. This documents the VIF rationale.
          \n  Other notable correlations (below VIF=50 threshold):
          - routeTotalDriveHours ↔ routeTotalStops: corr=0.496, VIF≈1.33 — fine.
          - stopSequence ↔ routeTotalStops: corr=0.381, VIF≈1.17 — fine.
        
        ### overlap
        
        ```
        [
          {
            \\"variant_id\\": \\"v_binary70_linear\\",
            \\"threshold\\": 0.01,
            \\"response_strategy\\": \\"TRIM\\",
            \\"trim_bounds\\": [0.001, 0.999]
          },
          {
            \\"variant_id\\": \\"v_binary70_nonparam\\",
            \\"threshold\\": 0.01,
            \\"response_strategy\\": \\"TRIM\\",
            \\"trim_bounds\\": [0.001, 0.999]
          }
        ]
        ```
        
        For both binary variants: treated=3,889 (0.69%) vs control=559,139 (99.31%). Ratio 1:144 is SEVERE. TRIM with generous bounds [0.001, 0.999]. The estimate is LATE — local to the overlap region where propensity is non-degenerate. Document that the vast majority of control observations will have propensity near zero and may be trimmed.
        
        ### variance
        
        ```
        [
          {
            \\"column\\": \\"nodeRefrigHealthPct\\",
            \\"structural_note\\": \\"Treatment is heavily right-skewed: mean=99.53, stddev=5.47. 99.31% of observations have health ≥70%. Only 0.69% fall below the 70% threshold. This is a RARE EVENT treatment — degradation events are extreme tail occurrences (compressor failure, refrigerant loss, power outage). The continuous variant captures the full distribution; the binary variant isolates the rare tail. Near-degeneracy is structural (equipment rarely fails), NOT data collection error.\\"
          },
          {
            \\"column\\": \\"excursionFlag\\",
            \\"structural_note\\": \\"Binary outcome with prevalence 6.50% (36,589 excursions out of 563,028 shipments). Moderate imbalance but adequate for DML. Theoretical max R² for binary outcome = 4×0.065×0.935 = 0.243.\\"
          }
        ]
        ```
        
        ---
        
        ## unmeasured_confounding
        
        ```
        [
          {
            \\"variant_id\\": \\"v_cont_linear\\",
            \\"method\\": \\"E_VALUE\\",
            \\"null_hypothesis\\": \\"ATE = 0\\",
            \\"notes\\": \\"How strong would an unmeasured confounder's association with both facility refrig health and excursion risk need to be to explain away the observed effect? Key unmeasured candidates: maintenance history (proactive maintenance schedule not observed at dispatch time), operator behavior (dispatch decisions during degradation), facility age.\\"
          },
          {
            \\"variant_id\\": \\"v_cont_linear\\",
            \\"method\\": \\"ROSENBAUM_BOUNDS\\",
            \\"null_hypothesis\\": \\"ATE = 0\\",
            \\"notes\\": \\"How large a departure from random assignment of refrig health levels would be needed to change inference? The near-exogenous nature of the treatment (random equipment failures) suggests Gamma should be high.\\"
          },
          {
            \\"variant_id\\": \\"v_binary70_linear\\",
            \\"method\\": \\"E_VALUE\\",
            \\"null_hypothesis\\": \\"ATE = 0\\",
            \\"notes\\": \\"Binary version: how strong would unmeasured confounding need to be to explain away the below-70% vs above-70% excursion rate difference? Large raw difference (49% vs 6.2%) suggests E-value will be high.\\"
          },
          {
            \\"variant_id\\": \\"v_binary70_linear\\",
            \\"method\\": \\"ROSENBAUM_BOUNDS\\",
            \\"null_hypothesis\\": \\"ATE = 0\\",
            \\"notes\\": \\"Rosenbaum bounds for the binary threshold variant. Given severe positivity violation, bounds apply only to the trimmed overlap region.\\"
          },
          {
            \\"variant_id\\": \\"v_cont_direct_linear\\",
            \\"method\\": \\"E_VALUE\\",
            \\"null_hypothesis\\": \\"ATE = 0\\",
            \\"notes\\": \\"E-value for the direct effect (blocking the preDepartureTempC mediator). If direct effect is small, the E-value threshold for nullification is lower.\\"
          }
        ]
        ```
        
        ---
        
        ## externalization
        
        ### domain_rankings
        
        ```
        [
          {
            \\"domain_ranking\\": [\\"nodePowerStatus_outage\\", \\"nodeRefrigHealthPct_below50\\", \\"nodeRefrigHealthPct_50to70\\", \\"nodeRefrigHealthPct_70to90\\", \\"nodeRefrigHealthPct_above90\\"],
            \\"source\\": \\"Domain knowledge: complete refrigeration failure (outage) is most severe, followed by graduated degradation levels. Equipment failure severity is well-established in cold chain literature.\\",
            \\"comparison_method\\": \\"Spearman rank correlation between domain severity ordering and GRF-derived excursion rate coefficients\\",
            \\"scope\\": \\"All regions — severity ordering should be universal regardless of climate\\",
            \\"expected_concordance\\": \\"positive (higher severity → higher excursion risk)\\"
          }
        ]
        ```
        
        ### allocation_bias
        
        ```
        [
          {
            \\"treatment_column\\": \\"nodeRefrigHealthPct\\",
            \\"grouping_column\\": \\"region\\",
            \\"flag_threshold\\": 0.10
          },
          {
            \\"treatment_column\\": \\"nodeRefrigHealthPct\\",
            \\"grouping_column\\": \\"nodeId\\",
            \\"flag_threshold\\": 0.10
          }
        ]
        ```
        
        Tests whether refrig degradation events are concentrated at specific nodes/regions (allocation bias). If degradation is concentrated at hot-climate nodes, the treatment estimate conflates facility-specific and climate effects despite nodeId being in W.
        
        ---
        
        ## discrepancy_log
        
        ```
        [
          {
            \\"field\\": \\"rare_event_n\\",
            \\"generator_value\\": \\"4,952 for '<70% health'\\",
            \\"compiler_value\\": \\"below70 = 3,889; below90 = 4,952. Generator's 4,952 corresponds to health <90%, not <70%.\\",
            \\"resolution\\": \\"Generator's severity tiers are mislabeled: 'Partial degradation (50-70%)' with n≈2,380 is actually 50-90% range (below90 - below50 = 4,952 - 2,572 = 2,380). Actual 50-70% range has 1,317 observations. The threshold at 70% is still correct (inflection analysis confirmed), but tier definitions use corrected boundaries. Pipeline uses verified counts: below70=3,889 for binary threshold, below50=2,572 for severe tier.\\"
          },
          {
            \\"field\\": \\"excursion_rate_below70\\",
            \\"generator_value\\": \\"~21.9% for 50-70% tier (actually 50-90%)\\",
            \\"compiler_value\\": \\"49.0% for all below-70 observations\\",
            \\"resolution\\": \\"Generator's 21.9% is the rate for health 50-90% (the mislabeled tier). The actual below-70% excursion rate is 49.0%. Above-70% rate is 6.2%. Pipeline uses the verified 49.0% vs 6.2% rates for sanity calibration.\\"
          }
        ]
        ```
        
        ---
        
        ## CHECKLIST — Mandatory Field Coverage
        
        | Section | Field | Covered | Notes |
        |---------|-------|---------|-------|
        | hypothesis_id | ✓ | `nodeRefrigHealthPct_excursionFlag` |
        | treatment | ✓ | `nodeRefrigHealthPct` |
        | outcome | ✓ | `excursionFlag` |
        | treatment_form | ✓ | CONTINUOUS (primary) + BINARY_THRESHOLD |
        | query | ✓ | Full DenseQueryDTO with JOIN |
        | expected_row_count | ✓ | 563,028 |
        | strip_columns | ✓ | 5 columns listed |
        | dag_edges | ✓ | 17 edges with mechanisms |
        | dsep_threshold | ✓ | 0.044 with 5-pair calibration |
        | adjustment_set | ✓ | 19 variables |
        | mediators_excluded | ✓ | preDepartureTempC |
        | estimation_variants | ✓ | 6 variants (3 Linear + 3 NonParam) |
        | gates.nuisance_r2 | ✓ | All 5 sub-fields |
        | gates.sanity | ✓ | Direction, abort, flag |
        | gates.placebo | ✓ | flag_ratio=0.15 |
        | mediation | ✓ | 1 mediator with total/direct variant IDs |
        | grf_configs | ✓ | 2 configs (primary + secondary) |
        | refutations | ✓ | PLACEBO, RANDOM_CAUSE, SUBSET |
        | sensitivity.confounder_drops | ✓ | 19 entries |
        | sensitivity.confounder_adds | ✓ | 5 entries |
        | sensitivity.threshold_variants | ✓ | 5 thresholds (50, 60, 70, 80, 90) |
        | sensitivity.model_variants | ✓ | 3 entries |
        | structural_breaks | ✓ | 3 configs (monthly×2 + quarterly×1) |
        | residual_checks.autocorrelation | ✓ | 2 grain levels |
        | residual_checks.field_correlation | ✓ | threshold + 28 check columns |
        | residual_checks.auto_correction | ✓ | max_iterations=3, stop=5% |
        | residual_checks.metadata_correlation | ✓ | 2 entries |
        | range_checks.vif | ✓ | threshold=50, 1 drop pair |
        | range_checks.overlap | ✓ | 2 binary variants |
        | range_checks.variance | ✓ | 2 entries (treatment + outcome) |
        | unmeasured_confounding | ✓ | 5 entries (E_VALUE + ROSENBAUM_BOUNDS) |
        | externalization.domain_rankings | ✓ | 1 entry |
        | externalization.allocation_bias | ✓ | 2 entries |
        | discrepancy_log | ✓ | 2 entries documenting tier count mislabeling |
        """;
}
