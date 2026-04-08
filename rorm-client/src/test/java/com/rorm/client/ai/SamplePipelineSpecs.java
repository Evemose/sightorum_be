package com.rorm.client.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.dto.dense.DenseQueryDto;
import com.rorm.ml.dto.PipelineSpecRequest;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Map;

final class SamplePipelineSpecs {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // ========================== H1: containerInsulationType → excursionFlag ==========================
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {};

    // ========================== H3: nodeRefrigHealthPct → excursionFlag ==========================
    private static final List<String> H1_W_COLUMNS = List.of(
        "preDepartureTempC", "ambientTempAtDispatchC", "nodeId",
        "vehicleMakeModel", "vehicleRefrigModel", "routeTotalStops",
        "routeTotalDriveHours", "routeTotalAirMiles", "stopSequence",
        "receivingDelayMin", "isAfterHoursArrival", "receivingDockTempControlled",
        "dayOfWeek", "dispatchMonth", "dispatchYear", "nodeRefrigHealthPct",
        "productClass", "palletPosition", "productMassAtStopKg",
        "containerAgeMonths", "vehicleRefrigAgeMonths", "vehicleInsulationRating",
        "vehicleCargoVolumeM3", "vehicleReeferKwRated", "nodePowerStatus",
        "siteType", "siteUrbanRural", "isHosRegulated", "loggerMonthsSinceCal"
    );

    // ========================== Type references ==========================
    private static final String H1_DAG_EDGES = """
        nodeId -> containerInsulationType; \
        nodeId -> preDepartureTempC; \
        nodeId -> ambientTempAtDispatchC; \
        dispatchMonth -> ambientTempAtDispatchC; \
        ambientTempAtDispatchC -> preDepartureTempC; \
        ambientTempAtDispatchC -> excursionFlag; \
        nodeRefrigHealthPct -> preDepartureTempC; \
        preDepartureTempC -> excursionFlag; \
        containerInsulationType -> excursionFlag; \
        stopSequence -> excursionFlag; \
        routeTotalDriveHours -> excursionFlag; \
        receivingDelayMin -> excursionFlag; \
        vehicleRefrigModel -> excursionFlag""";
    private static final String H1_QUERY = """
        {
          "from": "shipments",
          "fromAlias": "s",
          "joins": [
            {
              "joinType": "LEFT",
              "joinedRoot": {"rootName": "cold_nodes", "alias": "cn"},
              "onCondition": {
                "@type": "binary",
                "left": {"@type": "path", "path": "s.nodeId"},
                "operator": "EQUALS",
                "right": {"@type": "path", "path": "cn.nodeId"}
              }
            }
          ],
          "selector": {
            "@type": "multi",
            "expressions": [
              {"alias": "shipmentId", "expression": {"@type": "path", "path": "s.shipmentId"}},
              {"alias": "containerInsulationType", "expression": {"@type": "path", "path": "s.containerInsulationType"}},
              {"alias": "excursionFlag", "expression": {"@type": "path", "path": "s.excursionFlag"}},
              {"alias": "preDepartureTempC", "expression": {"@type": "path", "path": "s.preDepartureTempC"}},
              {"alias": "ambientTempAtDispatchC", "expression": {"@type": "path", "path": "s.ambientTempAtDispatchC"}},
              {"alias": "ambientTempAtArrivalC", "expression": {"@type": "path", "path": "s.ambientTempAtArrivalC"}},
              {"alias": "nodeId", "expression": {"@type": "path", "path": "s.nodeId"}},
              {"alias": "vehicleMakeModel", "expression": {"@type": "path", "path": "s.vehicleMakeModel"}},
              {"alias": "vehicleRefrigModel", "expression": {"@type": "path", "path": "s.vehicleRefrigModel"}},
              {"alias": "routeTotalStops", "expression": {"@type": "path", "path": "s.routeTotalStops"}},
              {"alias": "routeTotalDriveHours", "expression": {"@type": "path", "path": "s.routeTotalDriveHours"}},
              {"alias": "routeTotalAirMiles", "expression": {"@type": "path", "path": "s.routeTotalAirMiles"}},
              {"alias": "stopSequence", "expression": {"@type": "path", "path": "s.stopSequence"}},
              {"alias": "receivingDelayMin", "expression": {"@type": "path", "path": "s.receivingDelayMin"}},
              {"alias": "isAfterHoursArrival", "expression": {"@type": "function", "functionName": "CASE", "arguments": [
                {"@type": "binary", "left": {"@type": "path", "path": "s.isAfterHoursArrival"}, "operator": "EQUALS", "right": {"@type": "literal", "value": true}},
                {"@type": "literal", "value": 1},
                {"@type": "literal", "value": 0}
              ]}},
              {"alias": "receivingDockTempControlled", "expression": {"@type": "function", "functionName": "CASE", "arguments": [
                {"@type": "binary", "left": {"@type": "path", "path": "s.receivingDockTempControlled"}, "operator": "EQUALS", "right": {"@type": "literal", "value": true}},
                {"@type": "literal", "value": 1},
                {"@type": "literal", "value": 0}
              ]}},
              {"alias": "dayOfWeek", "expression": {"@type": "path", "path": "s.dayOfWeek"}},
              {"alias": "dispatchMonth", "expression": {"@type": "function", "functionName": "EXTRACT", "arguments": [
                {"@type": "literal", "value": "MONTH"},
                {"@type": "path", "path": "s.date"}
              ]}},
              {"alias": "dispatchYear", "expression": {"@type": "function", "functionName": "EXTRACT", "arguments": [
                {"@type": "literal", "value": "YEAR"},
                {"@type": "path", "path": "s.date"}
              ]}},
              {"alias": "nodeRefrigHealthPct", "expression": {"@type": "path", "path": "s.nodeRefrigHealthPct"}},
              {"alias": "productClass", "expression": {"@type": "path", "path": "s.productClass"}},
              {"alias": "palletPosition", "expression": {"@type": "path", "path": "s.palletPosition"}},
              {"alias": "productMassAtStopKg", "expression": {"@type": "path", "path": "s.productMassAtStopKg"}},
              {"alias": "containerAgeMonths", "expression": {"@type": "path", "path": "s.containerAgeMonths"}},
              {"alias": "vehicleRefrigAgeMonths", "expression": {"@type": "path", "path": "s.vehicleRefrigAgeMonths"}},
              {"alias": "vehicleInsulationRating", "expression": {"@type": "path", "path": "s.vehicleInsulationRating"}},
              {"alias": "vehicleCargoVolumeM3", "expression": {"@type": "path", "path": "s.vehicleCargoVolumeM3"}},
              {"alias": "vehicleReeferKwRated", "expression": {"@type": "path", "path": "s.vehicleReeferKwRated"}},
              {"alias": "nodePowerStatus", "expression": {"@type": "path", "path": "s.nodePowerStatus"}},
              {"alias": "siteType", "expression": {"@type": "path", "path": "s.siteType"}},
              {"alias": "siteUrbanRural", "expression": {"@type": "path", "path": "s.siteUrbanRural"}},
              {"alias": "isHosRegulated", "expression": {"@type": "function", "functionName": "CASE", "arguments": [
                {"@type": "binary", "left": {"@type": "path", "path": "s.isHosRegulated"}, "operator": "EQUALS", "right": {"@type": "literal", "value": true}},
                {"@type": "literal", "value": 1},
                {"@type": "literal", "value": 0}
              ]}},
              {"alias": "loggerMonthsSinceCal", "expression": {"@type": "path", "path": "s.loggerMonthsSinceCal"}},
              {"alias": "hasRedundancy", "expression": {"@type": "function", "functionName": "CASE", "arguments": [
                {"@type": "binary", "left": {"@type": "path", "path": "cn.hasRedundancy"}, "operator": "EQUALS", "right": {"@type": "literal", "value": true}},
                {"@type": "literal", "value": 1},
                {"@type": "literal", "value": 0}
              ]}},
              {"alias": "refrigSystemType", "expression": {"@type": "path", "path": "cn.refrigSystemType"}},
              {"alias": "region", "expression": {"@type": "path", "path": "s.hubId.region"}},
              {"alias": "date", "expression": {"@type": "path", "path": "s.date"}},
              {"alias": "loggerId", "expression": {"@type": "path", "path": "s.loggerId"}},
              {"alias": "containerWallAM2", "expression": {"@type": "path", "path": "s.containerId.wallAM2"}},
              {"alias": "containerDoorAM2", "expression": {"@type": "path", "path": "s.containerId.doorAM2"}}
            ]
          }
        }
        """;

    // ========================== H1 constants ==========================
    private static final String H1_ESTIMATION_VARIANTS = """
        [
          {
            "id": "primary_categorical_linear",
            "treatment_column": "containerInsulationType",
            "treatment_form": "CATEGORICAL",
            "model_type": "LinearDML",
            "w_columns": ["preDepartureTempC","ambientTempAtDispatchC","nodeId","vehicleMakeModel","vehicleRefrigModel","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","nodeRefrigHealthPct","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","vehicleReeferKwRated","nodePowerStatus","siteType","siteUrbanRural","isHosRegulated","loggerMonthsSinceCal"],
            "reference_category": "VIP_panel",
            "filter": {"column": "region", "operator": "IN", "values": ["Northeast_NJ","Midwest_IN","PacificNW_OR","Mountain_CO"]}
          },
          {
            "id": "primary_categorical_nonparam",
            "treatment_column": "containerInsulationType",
            "treatment_form": "CATEGORICAL",
            "model_type": "NonParamDML",
            "w_columns": ["preDepartureTempC","ambientTempAtDispatchC","nodeId","vehicleMakeModel","vehicleRefrigModel","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","nodeRefrigHealthPct","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","vehicleReeferKwRated","nodePowerStatus","siteType","siteUrbanRural","isHosRegulated","loggerMonthsSinceCal"],
            "reference_category": "VIP_panel",
            "filter": {"column": "region", "operator": "IN", "values": ["Northeast_NJ","Midwest_IN","PacificNW_OR","Mountain_CO"]}
          },
          {
            "id": "full_sample_categorical_linear",
            "treatment_column": "containerInsulationType",
            "treatment_form": "CATEGORICAL",
            "model_type": "LinearDML",
            "w_columns": ["preDepartureTempC","ambientTempAtDispatchC","nodeId","vehicleMakeModel","vehicleRefrigModel","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","nodeRefrigHealthPct","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","vehicleReeferKwRated","nodePowerStatus","siteType","siteUrbanRural","isHosRegulated","loggerMonthsSinceCal"],
            "reference_category": "VIP_panel"
          },
          {
            "id": "hot_zone_categorical_linear",
            "treatment_column": "containerInsulationType",
            "treatment_form": "CATEGORICAL",
            "model_type": "LinearDML",
            "w_columns": ["preDepartureTempC","ambientTempAtDispatchC","nodeId","vehicleMakeModel","vehicleRefrigModel","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","nodeRefrigHealthPct","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","vehicleReeferKwRated","nodePowerStatus","siteType","siteUrbanRural","isHosRegulated","loggerMonthsSinceCal"],
            "reference_category": "VIP_panel",
            "filter": {"column": "region", "operator": "IN", "values": ["Southwest_AZ","SouthCentral_TX","Southeast_GA"]}
          },
          {
            "id": "binary_pir_vs_vip_linear",
            "treatment_column": "containerInsulationType",
            "treatment_form": "CATEGORICAL",
            "model_type": "LinearDML",
            "w_columns": ["preDepartureTempC","ambientTempAtDispatchC","nodeId","vehicleMakeModel","vehicleRefrigModel","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","nodeRefrigHealthPct","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","vehicleReeferKwRated","nodePowerStatus","siteType","siteUrbanRural","isHosRegulated","loggerMonthsSinceCal"],
            "reference_category": "VIP_panel",
            "filter": {"AND": [
              {"column": "region", "operator": "IN", "values": ["Northeast_NJ","Midwest_IN","PacificNW_OR","Mountain_CO"]},
              {"column": "containerInsulationType", "operator": "IN", "values": ["PIR_foam","VIP_panel"]}
            ]}
          },
          {
            "id": "binary_pir_vs_vip_nonparam",
            "treatment_column": "containerInsulationType",
            "treatment_form": "CATEGORICAL",
            "model_type": "NonParamDML",
            "w_columns": ["preDepartureTempC","ambientTempAtDispatchC","nodeId","vehicleMakeModel","vehicleRefrigModel","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","nodeRefrigHealthPct","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","vehicleReeferKwRated","nodePowerStatus","siteType","siteUrbanRural","isHosRegulated","loggerMonthsSinceCal"],
            "reference_category": "VIP_panel",
            "filter": {"AND": [
              {"column": "region", "operator": "IN", "values": ["Northeast_NJ","Midwest_IN","PacificNW_OR","Mountain_CO"]},
              {"column": "containerInsulationType", "operator": "IN", "values": ["PIR_foam","VIP_panel"]}
            ]}
          }
        ]
        """;
    private static final String H1_GATES = """
        {
          "nuisance_r2": {
            "outcome_abort": 0.005,
            "outcome_flag": 0.05,
            "treatment_abort": 0.04,
            "treatment_flag": 0.15,
            "treatment_structural_max_r2": 0.75
          },
          "sanity": {
            "expected_direction": 1,
            "abort_magnitude": 0.05,
            "flag_magnitude": 0.025
          },
          "placebo": {
            "flag_ratio": 0.50
          }
        }
        """;
    private static final String H1_GRF_CONFIGS = """
        [
          {"id": "grf_primary_region", "modifier_columns": ["region", "dispatchMonth"], "slicing": {"region": "unique", "dispatchMonth": "unique"}},
          {"id": "grf_climate_continuous", "modifier_columns": ["ambientTempAtDispatchC", "routeTotalDriveHours", "stopSequence"], "slicing": {"ambientTempAtDispatchC": "quartile", "routeTotalDriveHours": "quartile", "stopSequence": "quartile"}},
          {"id": "grf_equipment_interaction", "modifier_columns": ["vehicleRefrigModel", "vehicleInsulationRating", "nodeRefrigHealthPct"], "slicing": {"vehicleRefrigModel": "unique", "vehicleInsulationRating": "quartile", "nodeRefrigHealthPct": "quartile"}}
        ]
        """;
    private static final String H1_REFUTATIONS = """
        [{"type": "PLACEBO"}, {"type": "RANDOM_CAUSE"}, {"type": "SUBSET"}, {"type": "TEMPORAL_PLACEBO"}]
        """;
    private static final String H1_SENSITIVITY = """
        {
          "confounder_drops": [
            {"column": "preDepartureTempC", "deviation_threshold_pct": 20},
            {"column": "ambientTempAtDispatchC", "deviation_threshold_pct": 20},
            {"column": "nodeId", "deviation_threshold_pct": 20},
            {"column": "vehicleMakeModel", "deviation_threshold_pct": 20},
            {"column": "vehicleRefrigModel", "deviation_threshold_pct": 20},
            {"column": "routeTotalStops", "deviation_threshold_pct": 20},
            {"column": "routeTotalDriveHours", "deviation_threshold_pct": 20},
            {"column": "routeTotalAirMiles", "deviation_threshold_pct": 20},
            {"column": "stopSequence", "deviation_threshold_pct": 20},
            {"column": "receivingDelayMin", "deviation_threshold_pct": 20},
            {"column": "isAfterHoursArrival", "deviation_threshold_pct": 20},
            {"column": "receivingDockTempControlled", "deviation_threshold_pct": 20},
            {"column": "dayOfWeek", "deviation_threshold_pct": 20},
            {"column": "dispatchMonth", "deviation_threshold_pct": 20},
            {"column": "dispatchYear", "deviation_threshold_pct": 20},
            {"column": "nodeRefrigHealthPct", "deviation_threshold_pct": 20},
            {"column": "productClass", "deviation_threshold_pct": 20},
            {"column": "palletPosition", "deviation_threshold_pct": 20},
            {"column": "productMassAtStopKg", "deviation_threshold_pct": 20},
            {"column": "containerAgeMonths", "deviation_threshold_pct": 20},
            {"column": "vehicleRefrigAgeMonths", "deviation_threshold_pct": 20},
            {"column": "vehicleInsulationRating", "deviation_threshold_pct": 20},
            {"column": "vehicleCargoVolumeM3", "deviation_threshold_pct": 20},
            {"column": "vehicleReeferKwRated", "deviation_threshold_pct": 20},
            {"column": "nodePowerStatus", "deviation_threshold_pct": 20},
            {"column": "siteType", "deviation_threshold_pct": 20},
            {"column": "siteUrbanRural", "deviation_threshold_pct": 20},
            {"column": "isHosRegulated", "deviation_threshold_pct": 20},
            {"column": "loggerMonthsSinceCal", "deviation_threshold_pct": 20}
          ],
          "confounder_adds": [
            {"column": "ambientTempAtArrivalC", "reasoning": "Excluded from primary W due to collinearity with ambientTempAtDispatchC (corr=0.992). Tests whether arrival-specific ambient exposure captures residual confounding."},
            {"column": "hasRedundancy", "reasoning": "Excluded as absorbed by nodeId. Tests whether node-level redundancy has residual effect beyond nodeId fixed effect."},
            {"column": "refrigSystemType", "reasoning": "Excluded as absorbed by nodeId. Tests node-level facility equipment confounding residual."},
            {"column": "containerWallAM2", "reasoning": "Excluded as bundled with treatment (stddev=0 within type). Tests whether controlling for the physical dimension separately absorbs treatment effect."}
          ],
          "threshold_variants": [],
          "model_variants": [
            {"primary_variant_id": "primary_categorical_linear", "alternative_model_type": "NonParamDML"},
            {"primary_variant_id": "binary_pir_vs_vip_linear", "alternative_model_type": "NonParamDML"}
          ]
        }
        """;
    private static final String H1_STRUCTURAL_BREAKS = """
        [
          {"id": "breaks_monthly_sensitive", "entity_column": "nodeId", "temporal_column": "date", "temporal_grain": "MONTH", "pelt_penalty": 4.79, "min_obs_per_period": 50, "known_events_tables": ["hub_interventions", "fleet_transitions"], "entity_count": 28, "temporal_points": 120},
          {"id": "breaks_monthly_conservative", "entity_column": "nodeId", "temporal_column": "date", "temporal_grain": "MONTH", "pelt_penalty": 14.37, "min_obs_per_period": 50, "known_events_tables": ["hub_interventions", "fleet_transitions"], "entity_count": 28, "temporal_points": 120}
        ]
        """;
    private static final String H1_RESIDUAL_CHECKS = """
        {
          "autocorrelation": [
            {"temporal_column": "date", "grain": "MONTH", "lags": [1, 2, 3, 6, 12], "threshold": 0.05}
          ],
          "field_correlation": {
            "threshold": 0.03,
            "check_columns": ["ambientTempAtArrivalC","containerWallAM2","containerDoorAM2","hasRedundancy","refrigSystemType","region","loggerId","preDepartureTempC","ambientTempAtDispatchC","nodeId","vehicleMakeModel","vehicleRefrigModel","routeTotalStops","routeTotalDriveHours","stopSequence","receivingDelayMin","isAfterHoursArrival","dispatchMonth","dispatchYear","nodeRefrigHealthPct","productClass","palletPosition","containerAgeMonths","vehicleRefrigAgeMonths","vehicleInsulationRating"]
          },
          "auto_correction": {
            "max_iterations": 3,
            "stop_criterion_ci_pct": 5.0
          },
          "metadata_correlation": [
            {"column": "loggerMonthsSinceCal", "threshold": 0.03, "alert_type": "MEASUREMENT_BIAS"},
            {"column": "loggerId", "threshold": 0.03, "alert_type": "MEASUREMENT_DEVICE_BIAS"}
          ]
        }
        """;
    private static final String H1_RANGE_CHECKS = """
        {
          "vif": {
            "threshold": 50,
            "drop_pairs": [{"keep": "ambientTempAtDispatchC", "drop": "ambientTempAtArrivalC", "reasoning": "corr=0.992, VIF~64. Keep dispatch (pre-treatment, causally prior)."}]
          },
          "overlap": [
            {"variant_id": "binary_pir_vs_vip_linear", "threshold": 0.05, "response_strategy": "TRIM", "trim_bounds": [0.01, 0.99]},
            {"variant_id": "binary_pir_vs_vip_nonparam", "threshold": 0.05, "response_strategy": "TRIM", "trim_bounds": [0.01, 0.99]}
          ],
          "variance": [
            {"column": "containerInsulationType", "structural_note": "4-level categorical. Approximately balanced: VIP=29.7%, PUR=24.2%, XPS=31.1%, PIR=15.0%."},
            {"column": "excursionFlag", "structural_note": "Binary outcome with 4.2% prevalence in temperate scope (~12,590 events)."},
            {"column": "nodePowerStatus", "structural_note": "Near-degenerate: 256 outage records out of 563,028 (0.045%)."}
          ]
        }
        """;
    private static final String H1_UNMEASURED_CONFOUNDING = """
        [
          {"variant_id": "primary_categorical_linear", "method": "E_VALUE", "null_hypothesis": "ATE = 0", "notes": "How strong would an unmeasured confounder need to be to explain the ~0.5-1.5pp ATE in temperate zones?"},
          {"variant_id": "primary_categorical_linear", "method": "ROSENBAUM_BOUNDS", "null_hypothesis": "ATE = 0", "notes": "Critical Gamma for the primary categorical estimate."},
          {"variant_id": "full_sample_categorical_linear", "method": "E_VALUE", "null_hypothesis": "ATE = 0", "notes": "Full-sample E-value. Effect may be smaller due to hot-zone reversal averaging."},
          {"variant_id": "hot_zone_categorical_linear", "method": "E_VALUE", "null_hypothesis": "ATE = 0", "notes": "Hot-zone E-value. Tests unmeasured confounding for the reversed VIP effect."},
          {"variant_id": "binary_pir_vs_vip_linear", "method": "E_VALUE", "null_hypothesis": "ATE = 0", "notes": "Binary contrast E-value for the extreme comparison."},
          {"variant_id": "binary_pir_vs_vip_linear", "method": "ROSENBAUM_BOUNDS", "null_hypothesis": "ATE = 0", "notes": "Rosenbaum bounds for the PIR vs VIP binary contrast."}
        ]
        """;
    private static final String H1_EXTERNALIZATION = """
        {
          "domain_rankings": [
            {"ordering": "(containerInsulationType=VIP_panel) > (containerInsulationType=PIR_foam) > (containerInsulationType=PUR_foam) > (containerInsulationType=XPS_foam)", "source": "Thermal conductivity values: VIP 3-7 mW/m*K, PIR/PUR ~20-24, XPS 28-35.", "scope": "Temperate zones only", "expected_concordance": 0.3}
          ],
          "allocation_bias": [
            {"treatment_column": "containerInsulationType", "grouping_column": "nodeId", "flag_threshold": 0.10},
            {"treatment_column": "containerInsulationType", "grouping_column": "region", "flag_threshold": 0.10}
          ]
        }
        """;
    private static final String H1_DISCREPANCY_LOG = """
        [
          {"field": "wallAM2/doorAM2 as derived features", "generator_value": "Included as DERIVED_FEATURES", "compiler_value": "Included in query but EXCLUDED from W as BUNDLED (stddev=0 within each insulation type)", "resolution": "Variables included in query for residual diagnostics and sensitivity but excluded from primary W."}
        ]
        """;
    private static final List<String> H3_W_COLUMNS = List.of(
        "nodeId", "ambientTempAtDispatchC", "containerInsulationType",
        "vehicleMakeModel", "vehicleRefrigModel", "routeTotalDriveHours",
        "routeTotalStops", "stopSequence", "receivingDelayMin",
        "isAfterHoursArrival", "receivingDockTempControlled", "dayOfWeek",
        "dispatchMonth", "dispatchYear", "productClass", "palletPosition",
        "productMassAtStopKg", "containerAgeMonths", "vehicleRefrigAgeMonths"
    );
    private static final List<String> H3_W_COLUMNS_WITH_MEDIATOR = List.of(
        "nodeId", "ambientTempAtDispatchC", "containerInsulationType",
        "vehicleMakeModel", "vehicleRefrigModel", "routeTotalDriveHours",
        "routeTotalStops", "stopSequence", "receivingDelayMin",
        "isAfterHoursArrival", "receivingDockTempControlled", "dayOfWeek",
        "dispatchMonth", "dispatchYear", "productClass", "palletPosition",
        "productMassAtStopKg", "containerAgeMonths", "vehicleRefrigAgeMonths",
        "preDepartureTempC"
    );
    private static final String H3_DAG_EDGES = """
        nodeRefrigHealthPct -> excursionFlag; \
        nodeRefrigHealthPct -> preDepartureTempC; \
        preDepartureTempC -> excursionFlag; \
        nodePowerStatus -> nodeRefrigHealthPct; \
        ambientTempAtDispatchC -> nodeRefrigHealthPct; \
        ambientTempAtDispatchC -> preDepartureTempC; \
        ambientTempAtDispatchC -> excursionFlag; \
        nodeId -> nodeRefrigHealthPct; \
        nodeId -> ambientTempAtDispatchC; \
        dispatchMonth -> ambientTempAtDispatchC; \
        dispatchYear -> vehicleMakeModel; \
        stopSequence -> excursionFlag; \
        containerInsulationType -> excursionFlag; \
        vehicleMakeModel -> excursionFlag; \
        vehicleRefrigModel -> excursionFlag; \
        productMassAtStopKg -> excursionFlag; \
        receivingDelayMin -> excursionFlag; \
        routeTotalDriveHours -> excursionFlag""";

    // ========================== H3 constants ==========================
    private static final String H3_QUERY = """
        {
          "from": "shipments",
          "fromAlias": "s",
          "joins": [
            {
              "joinType": "LEFT",
              "joinedRoot": {"rootName": "cold_nodes", "alias": "cn"},
              "onCondition": {
                "@type": "binary",
                "left": {"@type": "path", "path": "s.nodeId"},
                "operator": "EQUALS",
                "right": {"@type": "path", "path": "cn.nodeId"}
              }
            }
          ],
          "selector": {
            "@type": "multi",
            "expressions": [
              {"alias": "shipmentId", "expression": {"@type": "path", "path": "s.shipmentId"}},
              {"alias": "nodeRefrigHealthPct", "expression": {"@type": "path", "path": "s.nodeRefrigHealthPct"}},
              {"alias": "excursionFlag", "expression": {"@type": "path", "path": "s.excursionFlag"}},
              {"alias": "preDepartureTempC", "expression": {"@type": "path", "path": "s.preDepartureTempC"}},
              {"alias": "nodePowerStatus", "expression": {"@type": "path", "path": "s.nodePowerStatus"}},
              {"alias": "nodeId", "expression": {"@type": "path", "path": "s.nodeId"}},
              {"alias": "ambientTempAtDispatchC", "expression": {"@type": "path", "path": "s.ambientTempAtDispatchC"}},
              {"alias": "ambientTempAtArrivalC", "expression": {"@type": "path", "path": "s.ambientTempAtArrivalC"}},
              {"alias": "containerInsulationType", "expression": {"@type": "path", "path": "s.containerInsulationType"}},
              {"alias": "vehicleMakeModel", "expression": {"@type": "path", "path": "s.vehicleMakeModel"}},
              {"alias": "vehicleRefrigModel", "expression": {"@type": "path", "path": "s.vehicleRefrigModel"}},
              {"alias": "routeTotalDriveHours", "expression": {"@type": "path", "path": "s.routeTotalDriveHours"}},
              {"alias": "routeTotalStops", "expression": {"@type": "path", "path": "s.routeTotalStops"}},
              {"alias": "stopSequence", "expression": {"@type": "path", "path": "s.stopSequence"}},
              {"alias": "receivingDelayMin", "expression": {"@type": "path", "path": "s.receivingDelayMin"}},
              {"alias": "isAfterHoursArrival", "expression": {"@type": "path", "path": "s.isAfterHoursArrival"}},
              {"alias": "receivingDockTempControlled", "expression": {"@type": "path", "path": "s.receivingDockTempControlled"}},
              {"alias": "dayOfWeek", "expression": {"@type": "path", "path": "s.dayOfWeek"}},
              {"alias": "dispatchMonth", "expression": {"@type": "function", "functionName": "EXTRACT", "arguments": [{"@type": "literal", "value": "MONTH"}, {"@type": "path", "path": "s.date"}]}},
              {"alias": "dispatchYear", "expression": {"@type": "function", "functionName": "EXTRACT", "arguments": [{"@type": "literal", "value": "YEAR"}, {"@type": "path", "path": "s.date"}]}},
              {"alias": "productClass", "expression": {"@type": "path", "path": "s.productClass"}},
              {"alias": "palletPosition", "expression": {"@type": "path", "path": "s.palletPosition"}},
              {"alias": "productMassAtStopKg", "expression": {"@type": "path", "path": "s.productMassAtStopKg"}},
              {"alias": "containerAgeMonths", "expression": {"@type": "path", "path": "s.containerAgeMonths"}},
              {"alias": "vehicleRefrigAgeMonths", "expression": {"@type": "path", "path": "s.vehicleRefrigAgeMonths"}},
              {"alias": "hasRedundancy", "expression": {"@type": "path", "path": "cn.hasRedundancy"}},
              {"alias": "region", "expression": {"@type": "path", "path": "cn.region"}},
              {"alias": "refrigSystemType", "expression": {"@type": "path", "path": "cn.refrigSystemType"}},
              {"alias": "loggerMonthsSinceCal", "expression": {"@type": "path", "path": "s.loggerMonthsSinceCal"}},
              {"alias": "loggerId", "expression": {"@type": "path", "path": "s.loggerId"}},
              {"alias": "siteUrbanRural", "expression": {"@type": "path", "path": "s.siteUrbanRural"}},
              {"alias": "routeTotalAirMiles", "expression": {"@type": "path", "path": "s.routeTotalAirMiles"}},
              {"alias": "dispatchDate", "expression": {"@type": "path", "path": "s.date"}},
              {"alias": "dispatchTimestamp", "expression": {"@type": "path", "path": "s.dispatchTimestamp"}}
            ]
          }
        }
        """;
    private static final String H3_MEDIATORS_EXCLUDED = """
        [{"column": "preDepartureTempC", "pathway": "nodeRefrigHealthPct -> preDepartureTempC -> excursionFlag", "direct_effect_variant_id": "v_cont_direct_linear"}]
        """;
    private static final String H3_ESTIMATION_VARIANTS = """
        [
          {"id": "v_cont_linear", "treatment_column": "nodeRefrigHealthPct", "treatment_form": "CONTINUOUS", "model_type": "LinearDML",
           "w_columns": ["nodeId","ambientTempAtDispatchC","containerInsulationType","vehicleMakeModel","vehicleRefrigModel","routeTotalDriveHours","routeTotalStops","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths"]},
          {"id": "v_cont_nonparam", "treatment_column": "nodeRefrigHealthPct", "treatment_form": "CONTINUOUS", "model_type": "NonParamDML",
           "w_columns": ["nodeId","ambientTempAtDispatchC","containerInsulationType","vehicleMakeModel","vehicleRefrigModel","routeTotalDriveHours","routeTotalStops","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths"]},
          {"id": "v_binary70_linear", "treatment_column": "nodeRefrigHealthPct", "treatment_form": "BINARY_THRESHOLD", "threshold_value": 70, "model_type": "LinearDML",
           "w_columns": ["nodeId","ambientTempAtDispatchC","containerInsulationType","vehicleMakeModel","vehicleRefrigModel","routeTotalDriveHours","routeTotalStops","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths"]},
          {"id": "v_binary70_nonparam", "treatment_column": "nodeRefrigHealthPct", "treatment_form": "BINARY_THRESHOLD", "threshold_value": 70, "model_type": "NonParamDML",
           "w_columns": ["nodeId","ambientTempAtDispatchC","containerInsulationType","vehicleMakeModel","vehicleRefrigModel","routeTotalDriveHours","routeTotalStops","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths"]},
          {"id": "v_cont_direct_linear", "treatment_column": "nodeRefrigHealthPct", "treatment_form": "CONTINUOUS", "model_type": "LinearDML",
           "w_columns": ["nodeId","ambientTempAtDispatchC","containerInsulationType","vehicleMakeModel","vehicleRefrigModel","routeTotalDriveHours","routeTotalStops","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths","preDepartureTempC"]},
          {"id": "v_cont_direct_nonparam", "treatment_column": "nodeRefrigHealthPct", "treatment_form": "CONTINUOUS", "model_type": "NonParamDML",
           "w_columns": ["nodeId","ambientTempAtDispatchC","containerInsulationType","vehicleMakeModel","vehicleRefrigModel","routeTotalDriveHours","routeTotalStops","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths","preDepartureTempC"]}
        ]
        """;
    private static final String H3_GATES = """
        {
          "nuisance_r2": {
            "outcome_abort": 0.005,
            "outcome_flag": 0.04,
            "treatment_abort": 0.001,
            "treatment_flag": 0.005,
            "treatment_structural_max_r2": 0.10
          },
          "sanity": {
            "expected_direction": -1,
            "abort_magnitude": 0.03,
            "flag_magnitude": 0.01
          },
          "placebo": {
            "flag_ratio": 0.15
          }
        }
        """;
    private static final String H3_MEDIATION = """
        [{"mediator": "preDepartureTempC", "pathway": "nodeRefrigHealthPct -> preDepartureTempC -> excursionFlag", "total_variant_id": "v_cont_linear", "direct_variant_id": "v_cont_direct_linear"}]
        """;
    private static final String H3_GRF_CONFIGS = """
        [
          {"id": "grf_primary", "modifier_columns": ["nodePowerStatus","ambientTempAtDispatchC","hasRedundancy","region"], "slicing": {"nodePowerStatus": "unique", "ambientTempAtDispatchC": "quartile", "hasRedundancy": "unique", "region": "unique"}},
          {"id": "grf_secondary", "modifier_columns": ["containerInsulationType","vehicleRefrigModel","stopSequence","dispatchMonth","refrigSystemType"], "slicing": {"containerInsulationType": "unique", "vehicleRefrigModel": "unique", "stopSequence": "quartile", "dispatchMonth": "unique", "refrigSystemType": "unique"}}
        ]
        """;
    private static final String H3_REFUTATIONS = """
        [{"type": "PLACEBO"}, {"type": "RANDOM_CAUSE"}, {"type": "SUBSET"}]
        """;
    private static final String H3_SENSITIVITY = """
        {
          "confounder_drops": [
            {"column": "nodeId", "deviation_threshold_pct": 15},
            {"column": "ambientTempAtDispatchC", "deviation_threshold_pct": 15},
            {"column": "containerInsulationType", "deviation_threshold_pct": 25},
            {"column": "vehicleMakeModel", "deviation_threshold_pct": 25},
            {"column": "vehicleRefrigModel", "deviation_threshold_pct": 25},
            {"column": "routeTotalDriveHours", "deviation_threshold_pct": 25},
            {"column": "routeTotalStops", "deviation_threshold_pct": 25},
            {"column": "stopSequence", "deviation_threshold_pct": 25},
            {"column": "receivingDelayMin", "deviation_threshold_pct": 25},
            {"column": "isAfterHoursArrival", "deviation_threshold_pct": 25},
            {"column": "receivingDockTempControlled", "deviation_threshold_pct": 25},
            {"column": "dayOfWeek", "deviation_threshold_pct": 25},
            {"column": "dispatchMonth", "deviation_threshold_pct": 15},
            {"column": "dispatchYear", "deviation_threshold_pct": 15},
            {"column": "productClass", "deviation_threshold_pct": 25},
            {"column": "palletPosition", "deviation_threshold_pct": 25},
            {"column": "productMassAtStopKg", "deviation_threshold_pct": 25},
            {"column": "containerAgeMonths", "deviation_threshold_pct": 25},
            {"column": "vehicleRefrigAgeMonths", "deviation_threshold_pct": 25}
          ],
          "confounder_adds": [
            {"column": "nodePowerStatus", "reasoning": "Excluded from W as treatment mechanism. Adding tests confounding not mediated through nodeRefrigHealthPct."},
            {"column": "preDepartureTempC", "reasoning": "Excluded as mediator. Adding blocks mediator path (provides direct effect comparison)."},
            {"column": "ambientTempAtArrivalC", "reasoning": "Excluded for collinearity with ambientTempAtDispatchC."},
            {"column": "siteUrbanRural", "reasoning": "Not in primary W. Destination setting may affect both facility routing and outcome."},
            {"column": "routeTotalAirMiles", "reasoning": "Not in primary W (redundant with routeTotalDriveHours). Tests independent confounding information."}
          ],
          "threshold_variants": [
            {"threshold": 50, "expected_n_treated": 2572, "expected_n_control": 560456},
            {"threshold": 60, "expected_n_treated": 3189, "expected_n_control": 559839},
            {"threshold": 70, "expected_n_treated": 3889, "expected_n_control": 559139},
            {"threshold": 80, "expected_n_treated": 4411, "expected_n_control": 558617},
            {"threshold": 90, "expected_n_treated": 4952, "expected_n_control": 558076}
          ],
          "model_variants": [
            {"primary_variant_id": "v_cont_linear", "alternative_model_type": "NonParamDML"},
            {"primary_variant_id": "v_binary70_linear", "alternative_model_type": "NonParamDML"},
            {"primary_variant_id": "v_cont_direct_linear", "alternative_model_type": "NonParamDML"}
          ]
        }
        """;
    private static final String H3_STRUCTURAL_BREAKS = """
        [
          {"id": "breaks_monthly_sensitive", "entity_column": "nodeId", "temporal_column": "dispatchDate", "temporal_grain": "month", "pelt_penalty": 4.79, "min_obs_per_period": 100, "known_events_tables": ["hub_interventions","fleet_transitions"], "entity_count": 28, "temporal_points": 120},
          {"id": "breaks_monthly_conservative", "entity_column": "nodeId", "temporal_column": "dispatchDate", "temporal_grain": "month", "pelt_penalty": 14.37, "min_obs_per_period": 100, "known_events_tables": ["hub_interventions","fleet_transitions"], "entity_count": 28, "temporal_points": 120},
          {"id": "breaks_quarterly_sensitive", "entity_column": "nodeId", "temporal_column": "dispatchDate", "temporal_grain": "quarter", "pelt_penalty": 3.69, "min_obs_per_period": 300, "known_events_tables": ["hub_interventions","fleet_transitions"], "entity_count": 28, "temporal_points": 40}
        ]
        """;
    private static final String H3_RESIDUAL_CHECKS = """
        {
          "autocorrelation": [
            {"temporal_column": "dispatchDate", "grain": "month", "lags": [1, 3, 6, 12], "threshold": 0.03},
            {"temporal_column": "dispatchDate", "grain": "week", "lags": [1, 2, 4], "threshold": 0.03}
          ],
          "field_correlation": {
            "threshold": 0.025,
            "check_columns": ["preDepartureTempC","nodePowerStatus","hasRedundancy","region","refrigSystemType","loggerMonthsSinceCal","ambientTempAtArrivalC","routeTotalAirMiles","siteUrbanRural","nodeId","ambientTempAtDispatchC","containerInsulationType","vehicleMakeModel","vehicleRefrigModel","routeTotalDriveHours","routeTotalStops","stopSequence","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","dayOfWeek","dispatchMonth","dispatchYear","productClass","palletPosition","productMassAtStopKg","containerAgeMonths","vehicleRefrigAgeMonths"]
          },
          "auto_correction": {
            "max_iterations": 3,
            "stop_criterion_ci_pct": 5.0
          },
          "metadata_correlation": [
            {"column": "loggerMonthsSinceCal", "threshold": 0.02, "alert_type": "MEASUREMENT_BIAS"},
            {"column": "loggerId", "threshold": 0.02, "alert_type": "MEASUREMENT_BIAS"}
          ]
        }
        """;
    private static final String H3_RANGE_CHECKS = """
        {
          "vif": {
            "threshold": 50,
            "drop_pairs": [{"keep": "ambientTempAtDispatchC", "drop": "ambientTempAtArrivalC", "reasoning": "corr=0.992, VIF=62.5. Keep dispatch ambient (relevant to facility-level treatment mechanism)."}]
          },
          "overlap": [
            {"variant_id": "v_binary70_linear", "threshold": 0.01, "response_strategy": "TRIM", "trim_bounds": [0.001, 0.999]},
            {"variant_id": "v_binary70_nonparam", "threshold": 0.01, "response_strategy": "TRIM", "trim_bounds": [0.001, 0.999]}
          ],
          "variance": [
            {"column": "nodeRefrigHealthPct", "structural_note": "Treatment is heavily right-skewed: mean=99.53, stddev=5.47. 99.31% of observations have health >= 70%. Only 0.69% fall below threshold. RARE EVENT treatment."},
            {"column": "excursionFlag", "structural_note": "Binary outcome with prevalence 6.50% (36,589 excursions out of 563,028 shipments)."}
          ]
        }
        """;
    private static final String H3_UNMEASURED_CONFOUNDING = """
        [
          {"variant_id": "v_cont_linear", "method": "E_VALUE", "null_hypothesis": "ATE = 0", "notes": "How strong would an unmeasured confounder need to be? Key candidates: maintenance history, operator behavior, facility age."},
          {"variant_id": "v_cont_linear", "method": "ROSENBAUM_BOUNDS", "null_hypothesis": "ATE = 0", "notes": "Near-exogenous treatment (random equipment failures) suggests Gamma should be high."},
          {"variant_id": "v_binary70_linear", "method": "E_VALUE", "null_hypothesis": "ATE = 0", "notes": "Large raw difference (49% vs 6.2%) suggests E-value will be high."},
          {"variant_id": "v_binary70_linear", "method": "ROSENBAUM_BOUNDS", "null_hypothesis": "ATE = 0", "notes": "Rosenbaum bounds for binary threshold variant. Bounds apply only to trimmed overlap region."},
          {"variant_id": "v_cont_direct_linear", "method": "E_VALUE", "null_hypothesis": "ATE = 0", "notes": "E-value for the direct effect (blocking preDepartureTempC mediator)."}
        ]
        """;
    private static final String H3_EXTERNALIZATION = """
        {
          "domain_rankings": [
            {"ordering": "(50) > (60) > (70) > (80) > (90)", "source": "Domain knowledge: complete refrigeration failure is most severe, followed by graduated degradation levels.", "scope": "All regions", "expected_concordance": 0.5}
          ],
          "allocation_bias": [
            {"treatment_column": "nodeRefrigHealthPct", "grouping_column": "region", "flag_threshold": 0.10},
            {"treatment_column": "nodeRefrigHealthPct", "grouping_column": "nodeId", "flag_threshold": 0.10}
          ]
        }
        """;
    private static final String H3_DISCREPANCY_LOG = """
        [
          {"field": "rare_event_n", "generator_value": "4,952 for '<70% health'", "compiler_value": "below70 = 3,889; below90 = 4,952. Generator's 4,952 corresponds to health <90%, not <70%.", "resolution": "Pipeline uses verified counts: below70=3,889 for binary threshold, below50=2,572 for severe tier."},
          {"field": "excursion_rate_below70", "generator_value": "~21.9% for 50-70% tier (actually 50-90%)", "compiler_value": "49.0% for all below-70 observations", "resolution": "Pipeline uses verified 49.0% vs 6.2% rates for sanity calibration."}
        ]
        """;

    // ========================== H2: vehicleEquipmentCohort → excursionFlag ==========================

    private static final List<String> H2_W_COLUMNS = List.of(
        "nodeId", "ambientTempAtDispatchC", "routeTotalStops", "routeTotalDriveHours",
        "routeTotalAirMiles", "stopSequence", "containerInsulationType",
        "containerAgeMonths", "receivingDelayMin", "isAfterHoursArrival",
        "receivingDockTempControlled", "productClass", "palletPosition",
        "productMassAtStopKg", "dayOfWeek", "nodeRefrigHealthPct",
        "vehicleRefrigAgeMonths", "vehicleInsulationRating", "vehicleCargoVolumeM3",
        "loggerMonthsSinceCal", "siteType", "siteUrbanRural",
        "dispatchMonth", "dispatchYear"
    );

    private static final List<String> H2_W_COLUMNS_WITH_MEDIATOR = List.of(
        "nodeId", "ambientTempAtDispatchC", "routeTotalStops", "routeTotalDriveHours",
        "routeTotalAirMiles", "stopSequence", "containerInsulationType",
        "containerAgeMonths", "receivingDelayMin", "isAfterHoursArrival",
        "receivingDockTempControlled", "productClass", "palletPosition",
        "productMassAtStopKg", "dayOfWeek", "nodeRefrigHealthPct",
        "vehicleRefrigAgeMonths", "vehicleInsulationRating", "vehicleCargoVolumeM3",
        "loggerMonthsSinceCal", "siteType", "siteUrbanRural",
        "dispatchMonth", "dispatchYear",
        "preDepartureTempC"
    );

    private static final String H2_DAG_EDGES = """
        vehicleEquipmentCohort -> excursionFlag; \
        nodeId -> vehicleEquipmentCohort; \
        nodeId -> excursionFlag; \
        nodeId -> ambientTempAtDispatchC; \
        ambientTempAtDispatchC -> preDepartureTempC; \
        ambientTempAtDispatchC -> excursionFlag; \
        preDepartureTempC -> excursionFlag; \
        vehicleEquipmentCohort -> preDepartureTempC; \
        routeTotalDriveHours -> excursionFlag; \
        stopSequence -> excursionFlag; \
        containerInsulationType -> excursionFlag; \
        receivingDelayMin -> excursionFlag; \
        dispatchMonth -> ambientTempAtDispatchC; \
        dispatchYear -> vehicleEquipmentCohort; \
        nodeRefrigHealthPct -> preDepartureTempC""";

    private static final String H2_QUERY = """
        {
          "from": "shipments",
          "fromAlias": "s",
          "selector": {
            "@type": "multi",
            "expressions": [
              {"alias": "shipmentId", "expression": {"@type": "path", "path": "shipmentId"}},
              {"alias": "vehicleEquipmentCohort", "expression": {"@type": "function", "functionName": "CONCAT", "arguments": [{"@type": "path", "path": "vehicleMakeModel"}, {"@type": "literal", "value": " x "}, {"@type": "path", "path": "vehicleRefrigModel"}]}},
              {"alias": "vehicleMakeModel", "expression": {"@type": "path", "path": "vehicleMakeModel"}},
              {"alias": "vehicleRefrigModel", "expression": {"@type": "path", "path": "vehicleRefrigModel"}},
              {"alias": "excursionFlag", "expression": {"@type": "path", "path": "excursionFlag"}},
              {"alias": "nodeId", "expression": {"@type": "path", "path": "nodeId"}},
              {"alias": "ambientTempAtDispatchC", "expression": {"@type": "path", "path": "ambientTempAtDispatchC"}},
              {"alias": "ambientTempAtArrivalC", "expression": {"@type": "path", "path": "ambientTempAtArrivalC"}},
              {"alias": "routeTotalStops", "expression": {"@type": "path", "path": "routeTotalStops"}},
              {"alias": "routeTotalDriveHours", "expression": {"@type": "path", "path": "routeTotalDriveHours"}},
              {"alias": "routeTotalAirMiles", "expression": {"@type": "path", "path": "routeTotalAirMiles"}},
              {"alias": "stopSequence", "expression": {"@type": "path", "path": "stopSequence"}},
              {"alias": "containerInsulationType", "expression": {"@type": "path", "path": "containerInsulationType"}},
              {"alias": "containerAgeMonths", "expression": {"@type": "path", "path": "containerAgeMonths"}},
              {"alias": "receivingDelayMin", "expression": {"@type": "path", "path": "receivingDelayMin"}},
              {"alias": "isAfterHoursArrival", "expression": {"@type": "path", "path": "isAfterHoursArrival"}},
              {"alias": "receivingDockTempControlled", "expression": {"@type": "path", "path": "receivingDockTempControlled"}},
              {"alias": "productClass", "expression": {"@type": "path", "path": "productClass"}},
              {"alias": "palletPosition", "expression": {"@type": "path", "path": "palletPosition"}},
              {"alias": "productMassAtStopKg", "expression": {"@type": "path", "path": "productMassAtStopKg"}},
              {"alias": "dayOfWeek", "expression": {"@type": "path", "path": "dayOfWeek"}},
              {"alias": "nodeRefrigHealthPct", "expression": {"@type": "path", "path": "nodeRefrigHealthPct"}},
              {"alias": "vehicleRefrigAgeMonths", "expression": {"@type": "path", "path": "vehicleRefrigAgeMonths"}},
              {"alias": "vehicleInsulationRating", "expression": {"@type": "path", "path": "vehicleInsulationRating"}},
              {"alias": "vehicleCargoVolumeM3", "expression": {"@type": "path", "path": "vehicleCargoVolumeM3"}},
              {"alias": "loggerMonthsSinceCal", "expression": {"@type": "path", "path": "loggerMonthsSinceCal"}},
              {"alias": "siteType", "expression": {"@type": "path", "path": "siteType"}},
              {"alias": "siteUrbanRural", "expression": {"@type": "path", "path": "siteUrbanRural"}},
              {"alias": "preDepartureTempC", "expression": {"@type": "path", "path": "preDepartureTempC"}},
              {"alias": "date", "expression": {"@type": "path", "path": "date"}},
              {"alias": "region", "expression": {"@type": "path", "path": "hubId.region"}},
              {"alias": "climateZone", "expression": {"@type": "path", "path": "hubId.climateZone"}},
              {"alias": "loggerId", "expression": {"@type": "path", "path": "loggerId"}},
              {"alias": "isHosRegulated", "expression": {"@type": "path", "path": "isHosRegulated"}},
              {"alias": "reroutedFlag", "expression": {"@type": "path", "path": "reroutedFlag"}},
              {"alias": "dispatchMonth", "expression": {"@type": "function", "functionName": "EXTRACT", "arguments": [{"@type": "literal", "value": "MONTH"}, {"@type": "path", "path": "date"}]}},
              {"alias": "dispatchYear", "expression": {"@type": "function", "functionName": "EXTRACT", "arguments": [{"@type": "literal", "value": "YEAR"}, {"@type": "path", "path": "date"}]}}
            ]
          }
        }
        """;

    private static final String H2_MEDIATORS_EXCLUDED = """
        [{"column": "preDepartureTempC", "pathway": "vehicleEquipmentCohort -> preDepartureTempC -> excursionFlag", "direct_effect_variant_id": "V3"}]
        """;

    private static final String H2_POSITIVITY_CHECK = """
        {
          "treatment_hierarchy": ["vehicleEquipmentCohort", "vehicleRefrigModel", "vehicleMakeModel"]
        }
        """;

    private static final String H2_ESTIMATION_VARIANTS = """
        [
          {"id": "V1", "treatment_column": "vehicleEquipmentCohort", "treatment_form": "CATEGORICAL", "model_type": "LinearDML",
           "w_columns": ["nodeId","ambientTempAtDispatchC","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","containerInsulationType","containerAgeMonths","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","productClass","palletPosition","productMassAtStopKg","dayOfWeek","nodeRefrigHealthPct","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","loggerMonthsSinceCal","siteType","siteUrbanRural","dispatchMonth","dispatchYear"],
           "reference_category": "Ford_E-Transit x Thermo_King_Advancer_A400",
           "notes": "Reference = lowest excursion rate (2.587%, n=6,456). All ATEs relative to this best-performing cohort."},
          {"id": "V2", "treatment_column": "vehicleEquipmentCohort", "treatment_form": "CATEGORICAL", "model_type": "NonParamDML",
           "w_columns": ["nodeId","ambientTempAtDispatchC","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","containerInsulationType","containerAgeMonths","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","productClass","palletPosition","productMassAtStopKg","dayOfWeek","nodeRefrigHealthPct","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","loggerMonthsSinceCal","siteType","siteUrbanRural","dispatchMonth","dispatchYear"],
           "reference_category": "Ford_E-Transit x Thermo_King_Advancer_A400",
           "notes": "Tests whether linear outcome model is adequate. If V2 diverges >20% from V1, nonlinear confounding present."},
          {"id": "V3", "treatment_column": "vehicleEquipmentCohort", "treatment_form": "CATEGORICAL", "model_type": "LinearDML",
           "w_columns": ["nodeId","ambientTempAtDispatchC","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","containerInsulationType","containerAgeMonths","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","productClass","palletPosition","productMassAtStopKg","dayOfWeek","nodeRefrigHealthPct","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","loggerMonthsSinceCal","siteType","siteUrbanRural","dispatchMonth","dispatchYear","preDepartureTempC"],
           "reference_category": "Ford_E-Transit x Thermo_King_Advancer_A400",
           "notes": "Direct effect variant. With preDepartureTempC controlled, measures equipment effect NOT mediated through pre-departure temp. V1 - V3 = indirect effect via thermal buffer."},
          {"id": "V4", "treatment_column": "vehicleEquipmentCohort", "treatment_form": "CATEGORICAL", "model_type": "LinearDML",
           "w_columns": ["nodeId","ambientTempAtDispatchC","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","containerInsulationType","containerAgeMonths","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","productClass","palletPosition","productMassAtStopKg","dayOfWeek","nodeRefrigHealthPct","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","loggerMonthsSinceCal","siteType","siteUrbanRural","dispatchMonth","dispatchYear"],
           "reference_category": "Ford_E-Transit x Daikin_ZeSTIA",
           "filter": {"column": "vehicleEquipmentCohort", "operator": "IN", "values": ["Ford_E-Transit x Carrier_Vector_HE_19","Ford_E-Transit x Daikin_ZeSTIA"]},
           "notes": "Isolates reefer effect on same Ford_E-Transit chassis. CV_HE_19 = 11.71%, DK_ZeSTIA = 4.65%. n = 5,842 + 27,215 = 33,057. Generator flagged CV_HE_19 as worst despite being Gen3."},
          {"id": "V5", "treatment_column": "vehicleEquipmentCohort", "treatment_form": "CATEGORICAL", "model_type": "LinearDML",
           "w_columns": ["nodeId","ambientTempAtDispatchC","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","containerInsulationType","containerAgeMonths","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","productClass","palletPosition","productMassAtStopKg","dayOfWeek","nodeRefrigHealthPct","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","loggerMonthsSinceCal","siteType","siteUrbanRural","dispatchMonth","dispatchYear"],
           "reference_category": "Ford_E-450 x Thermo_King_T-880R",
           "filter": {"column": "vehicleEquipmentCohort", "operator": "IN", "values": ["Ford_E-Transit x Carrier_Vector_HE_19","Ford_E-450 x Thermo_King_T-880R"]},
           "notes": "Cross-generation contrast. E-Transit x CV_HE_19 (Gen3) = 11.71% vs E-450 x TK_T-880R (Gen1) = 4.05%. Tests generator claim that Gen3 is NOT always better. n = 5,842 + 35,312 = 41,154."}
        ]
        """;

    private static final String H2_GATES = """
        {
          "nuisance_r2": {
            "outcome_abort": 0.005,
            "outcome_flag": 0.04,
            "treatment_abort": 0.005,
            "treatment_flag": 0.05,
            "treatment_structural_max_r2": 0.96
          },
          "sanity": {
            "expected_direction": 1,
            "abort_magnitude": 0.20,
            "flag_magnitude": 0.12
          },
          "placebo": {
            "flag_ratio": 0.15
          }
        }
        """;

    private static final String H2_MEDIATION = """
        [{"mediator": "preDepartureTempC", "pathway": "vehicleEquipmentCohort -> preDepartureTempC -> excursionFlag", "total_variant_id": "V1", "direct_variant_id": "V3"}]
        """;

    private static final String H2_GRF_CONFIGS = """
        [
          {"id": "GRF-1", "modifier_columns": ["nodeId","climateZone","ambientTempAtDispatchC"], "slicing": {"nodeId": "unique", "climateZone": "unique", "ambientTempAtDispatchC": "quartile"}},
          {"id": "GRF-2", "modifier_columns": ["vehicleRefrigModel","vehicleMakeModel"], "slicing": {"vehicleRefrigModel": "unique", "vehicleMakeModel": "unique"}},
          {"id": "GRF-3", "modifier_columns": ["routeTotalDriveHours","containerInsulationType","stopSequence"], "slicing": {"containerInsulationType": "unique", "routeTotalDriveHours": "quartile", "stopSequence": "quartile"}}
        ]
        """;

    private static final String H2_REFUTATIONS = """
        [{"type": "PLACEBO"}, {"type": "RANDOM_CAUSE"}, {"type": "SUBSET"}, {"type": "TEMPORAL_PLACEBO"}]
        """;

    private static final String H2_SENSITIVITY = """
        {
          "confounder_drops": [
            {"column": "nodeId", "deviation_threshold_pct": 15},
            {"column": "ambientTempAtDispatchC", "deviation_threshold_pct": 15},
            {"column": "routeTotalStops", "deviation_threshold_pct": 20},
            {"column": "routeTotalDriveHours", "deviation_threshold_pct": 20},
            {"column": "routeTotalAirMiles", "deviation_threshold_pct": 20},
            {"column": "stopSequence", "deviation_threshold_pct": 20},
            {"column": "containerInsulationType", "deviation_threshold_pct": 20},
            {"column": "containerAgeMonths", "deviation_threshold_pct": 25},
            {"column": "receivingDelayMin", "deviation_threshold_pct": 20},
            {"column": "isAfterHoursArrival", "deviation_threshold_pct": 25},
            {"column": "receivingDockTempControlled", "deviation_threshold_pct": 25},
            {"column": "productClass", "deviation_threshold_pct": 25},
            {"column": "palletPosition", "deviation_threshold_pct": 25},
            {"column": "productMassAtStopKg", "deviation_threshold_pct": 25},
            {"column": "dayOfWeek", "deviation_threshold_pct": 25},
            {"column": "nodeRefrigHealthPct", "deviation_threshold_pct": 20},
            {"column": "vehicleRefrigAgeMonths", "deviation_threshold_pct": 25},
            {"column": "vehicleInsulationRating", "deviation_threshold_pct": 25},
            {"column": "vehicleCargoVolumeM3", "deviation_threshold_pct": 25},
            {"column": "loggerMonthsSinceCal", "deviation_threshold_pct": 20},
            {"column": "siteType", "deviation_threshold_pct": 25},
            {"column": "siteUrbanRural", "deviation_threshold_pct": 25},
            {"column": "dispatchMonth", "deviation_threshold_pct": 15},
            {"column": "dispatchYear", "deviation_threshold_pct": 15}
          ],
          "confounder_adds": [
            {"column": "ambientTempAtArrivalC", "reasoning": "Excluded from primary W due to extreme collinearity with ambientTempAtDispatchC (r=0.992). Test if arrival-side ambient captures transit-end conditions missed by dispatch-side measure."},
            {"column": "climateZone", "reasoning": "Partially absorbed by nodeId (4 nodes per region x 5 climate zones). Test if climate-level confounding beyond nodeId matters."},
            {"column": "isHosRegulated", "reasoning": "HOS regulation affects driver rest patterns and route timing. Not obviously a confounder but could interact with transit duration. 2.8% true."},
            {"column": "reroutedFlag", "reasoning": "Rerouting might indicate unusual conditions. Ambiguous temporality (could be post-treatment if caused by vehicle issues). 0.65% true."}
          ],
          "threshold_variants": [],
          "model_variants": [
            {"primary_variant_id": "V1", "alternative_model_type": "NonParamDML"},
            {"primary_variant_id": "V4", "alternative_model_type": "NonParamDML"},
            {"primary_variant_id": "V5", "alternative_model_type": "NonParamDML"}
          ]
        }
        """;

    private static final String H2_STRUCTURAL_BREAKS = """
        [
          {"id": "SB-1", "entity_column": "nodeId", "temporal_column": "date", "temporal_grain": "M", "pelt_penalty": 4.79, "min_obs_per_period": 30, "known_events_tables": ["hub_interventions","fleet_transitions"], "entity_count": 28, "temporal_points": 120},
          {"id": "SB-2", "entity_column": "nodeId", "temporal_column": "date", "temporal_grain": "M", "pelt_penalty": 14.37, "min_obs_per_period": 30, "known_events_tables": ["hub_interventions","fleet_transitions"], "entity_count": 28, "temporal_points": 120},
          {"id": "SB-3", "entity_column": "nodeId", "temporal_column": "date", "temporal_grain": "Y", "pelt_penalty": 2.30, "min_obs_per_period": 100, "known_events_tables": ["hub_interventions","fleet_transitions"], "entity_count": 28, "temporal_points": 10}
        ]
        """;

    private static final String H2_RESIDUAL_CHECKS = """
        {
          "autocorrelation": [
            {"temporal_column": "date", "grain": "M", "lags": [1, 2, 3, 6, 12], "threshold": 0.05}
          ],
          "field_correlation": {
            "threshold": 0.03,
            "check_columns": ["nodeId","ambientTempAtDispatchC","ambientTempAtArrivalC","routeTotalStops","routeTotalDriveHours","routeTotalAirMiles","stopSequence","containerInsulationType","containerAgeMonths","receivingDelayMin","isAfterHoursArrival","receivingDockTempControlled","productClass","palletPosition","productMassAtStopKg","dayOfWeek","nodeRefrigHealthPct","vehicleRefrigAgeMonths","vehicleInsulationRating","vehicleCargoVolumeM3","loggerMonthsSinceCal","siteType","siteUrbanRural","dispatchMonth","dispatchYear","preDepartureTempC","region","climateZone","loggerId","vehicleMakeModel","vehicleRefrigModel","isHosRegulated","reroutedFlag"]
          },
          "auto_correction": {
            "max_iterations": 3,
            "stop_criterion_ci_pct": 10
          },
          "metadata_correlation": [
            {"column": "loggerMonthsSinceCal", "threshold": 0.03, "alert_type": "FLAG"},
            {"column": "loggerId", "threshold": 0.03, "alert_type": "FLAG"}
          ]
        }
        """;

    private static final String H2_RANGE_CHECKS = """
        {
          "vif": {
            "threshold": 50,
            "drop_pairs": [{"keep": "ambientTempAtDispatchC", "drop": "ambientTempAtArrivalC", "reasoning": "corr=0.992, VIF>>50. ambientTempAtArrivalC excluded from primary W but present in query for confounder_adds. Keep dispatch-side (measured earlier, more causally proximate to pre-departure conditions)."}]
          },
          "overlap": [
            {"variant_id": "V4", "threshold": 0.05, "response_strategy": "TRIM", "trim_bounds": [0.02, 0.98]},
            {"variant_id": "V5", "threshold": 0.05, "response_strategy": "TRIM", "trim_bounds": [0.02, 0.98]}
          ],
          "variance": [
            {"column": "vehicleEquipmentCohort", "structural_note": "27 categorical levels. Smallest cohort: RAM_ProMaster_3500 x Thermo_King_Advancer_A400 (n=4,150, 0.74%). Largest: Freightliner_M2_112 x Thermo_King_T-680Pro (n=48,012, 8.5%). No near-degeneracy — all levels have >4000 obs."},
            {"column": "excursionFlag", "structural_note": "Binary with 6.5% prevalence. Not near-degenerate but rare-event; tree-based nuisance models should handle class imbalance."}
          ]
        }
        """;

    private static final String H2_UNMEASURED_CONFOUNDING = """
        [
          {"variant_id": "V1", "method": "E_VALUE", "null_hypothesis": "ATE = 0", "notes": "Primary full-interaction estimate. E-value tests what unmeasured confounder strength would nullify the worst-vs-best cohort difference."},
          {"variant_id": "V1", "method": "ROSENBAUM_BOUNDS", "null_hypothesis": "ATE = 0", "notes": "Sensitivity to departures from quasi-random equipment assignment after controlling for nodeId and other confounders."},
          {"variant_id": "V4", "method": "E_VALUE", "null_hypothesis": "ATE = 0", "notes": "Same-chassis binary contrast (E-Transit). If a single unmeasured confounder (e.g., driver skill assignment correlated with reefer model) explains the 7pp gap, E-value quantifies required strength."},
          {"variant_id": "V4", "method": "ROSENBAUM_BOUNDS", "null_hypothesis": "ATE = 0", "notes": "Rosenbaum bounds on the targeted same-chassis contrast."},
          {"variant_id": "V5", "method": "E_VALUE", "null_hypothesis": "ATE = 0", "notes": "Cross-generation contrast. Tests robustness of the Gen1 can outperform Gen3 finding."},
          {"variant_id": "V5", "method": "ROSENBAUM_BOUNDS", "null_hypothesis": "ATE = 0", "notes": "Rosenbaum bounds on the cross-generation contrast."},
          {"variant_id": "V3", "method": "E_VALUE", "null_hypothesis": "ATE = 0", "notes": "Direct effect variant. E-value for the equipment effect after blocking mediation through preDepartureTempC."}
        ]
        """;

    private static final String H2_EXTERNALIZATION = """
        {
          "domain_rankings": [
            {"ordering": "(vehicleRefrigModel=Daikin_RKN) > (vehicleRefrigModel=Daikin_LXE10, vehicleRefrigModel=Carrier_Vector_8611MT) > (vehicleRefrigModel=Carrier_Supra_860, vehicleRefrigModel=Thermo_King_T-680Pro) > (vehicleRefrigModel=Thermo_King_T-880R) > (vehicleRefrigModel=Daikin_ZeSTIA) > (vehicleRefrigModel=Thermo_King_Advancer_A400)", "source": "Domain knowledge: newer refrigeration models (Advancer_A400=TK flagship, ZeSTIA=Daikin flagship) have better compressor efficiency, refrigerant management, and defrost cycle control. Older models (RKN, LXE10) have known performance limitations. Generator EVIDENCE: TK_Advancer_A400 avg=3.98% (best), Daikin_RKN avg=8.43% (worst).", "scope": "All climate zones, averaged across chassis pairings. Evaluated via GRF-2 vehicleRefrigModel slicing.", "expected_concordance": 0.6}
          ],
          "allocation_bias": [
            {"treatment_column": "vehicleEquipmentCohort", "grouping_column": "nodeId", "flag_threshold": 0.10},
            {"treatment_column": "vehicleRefrigModel", "grouping_column": "nodeId", "flag_threshold": 0.10}
          ]
        }
        """;

    private static final String H2_DISCREPANCY_LOG = """
        [{"field": "node_count_in_nodeId_profile", "generator_value": "28 nodes", "compiler_value": "28 nodes — nodeId profile shows 20 top values but entity cold_nodes has 28 rows. Full node list confirmed via cold_nodes query: NOD_000 through NOD_027.", "resolution": "No discrepancy. Profile top-values truncated at 20; full set verified."}]
        """;

    private SamplePipelineSpecs() {
    }

    @SneakyThrows
    static PipelineSpecRequest h2(ObjectMapper mapper) {
        return PipelineSpecRequest.builder()
            .hypothesisId("vehicleEquipmentCohort_excursionFlag")
            .treatment("vehicleEquipmentCohort")
            .outcome("excursionFlag")
            .treatmentForm("CATEGORICAL")
            .dataQuery(mapper.readValue(H2_QUERY, DenseQueryDto.class))
            .expectedRowCount(563_028)
            .stripColumns(List.of("shipmentId"))
            .dagEdges(H2_DAG_EDGES)
            .dsepThreshold(0.034)
            .adjustmentSet(H2_W_COLUMNS)
            .mediatorsExcluded(mapper.readValue(H2_MEDIATORS_EXCLUDED, LIST_OF_MAPS))
            .positivityCheck(mapper.readValue(H2_POSITIVITY_CHECK, MAP_TYPE))
            .estimationVariants(mapper.readValue(H2_ESTIMATION_VARIANTS, LIST_OF_MAPS))
            .gates(mapper.readValue(H2_GATES, MAP_TYPE))
            .mediation(mapper.readValue(H2_MEDIATION, LIST_OF_MAPS))
            .grfConfigs(mapper.readValue(H2_GRF_CONFIGS, LIST_OF_MAPS))
            .refutations(mapper.readValue(H2_REFUTATIONS, LIST_OF_MAPS))
            .sensitivity(mapper.readValue(H2_SENSITIVITY, MAP_TYPE))
            .structuralBreaks(mapper.readValue(H2_STRUCTURAL_BREAKS, LIST_OF_MAPS))
            .residualChecks(mapper.readValue(H2_RESIDUAL_CHECKS, MAP_TYPE))
            .rangeChecks(mapper.readValue(H2_RANGE_CHECKS, MAP_TYPE))
            .unmeasuredConfounding(mapper.readValue(H2_UNMEASURED_CONFOUNDING, LIST_OF_MAPS))
            .externalization(mapper.readValue(H2_EXTERNALIZATION, MAP_TYPE))
            .discrepancyLog(mapper.readValue(H2_DISCREPANCY_LOG, LIST_OF_MAPS))
            .build();
    }

    @SneakyThrows
    static PipelineSpecRequest h1(ObjectMapper mapper) {
        return PipelineSpecRequest.builder()
            .hypothesisId("containerInsulationType_excursionFlag")
            .treatment("containerInsulationType")
            .outcome("excursionFlag")
            .treatmentForm("CATEGORICAL")
            .dataQuery(mapper.readValue(H1_QUERY, DenseQueryDto.class))
            .expectedRowCount(563_028)
            .stripColumns(List.of("shipmentId"))
            .dagEdges(H1_DAG_EDGES)
            .dsepThreshold(0.034)
            .adjustmentSet(H1_W_COLUMNS)
            .mediatorsExcluded(null)
            .estimationVariants(mapper.readValue(H1_ESTIMATION_VARIANTS, LIST_OF_MAPS))
            .gates(mapper.readValue(H1_GATES, MAP_TYPE))
            .mediation(null)
            .grfConfigs(mapper.readValue(H1_GRF_CONFIGS, LIST_OF_MAPS))
            .refutations(mapper.readValue(H1_REFUTATIONS, LIST_OF_MAPS))
            .sensitivity(mapper.readValue(H1_SENSITIVITY, MAP_TYPE))
            .structuralBreaks(mapper.readValue(H1_STRUCTURAL_BREAKS, LIST_OF_MAPS))
            .residualChecks(mapper.readValue(H1_RESIDUAL_CHECKS, MAP_TYPE))
            .rangeChecks(mapper.readValue(H1_RANGE_CHECKS, MAP_TYPE))
            .unmeasuredConfounding(mapper.readValue(H1_UNMEASURED_CONFOUNDING, LIST_OF_MAPS))
            .externalization(mapper.readValue(H1_EXTERNALIZATION, MAP_TYPE))
            .discrepancyLog(mapper.readValue(H1_DISCREPANCY_LOG, LIST_OF_MAPS))
            .build();
    }

    @SneakyThrows
    static PipelineSpecRequest h3(ObjectMapper mapper) {
        return PipelineSpecRequest.builder()
            .hypothesisId("nodeRefrigHealthPct_excursionFlag")
            .treatment("nodeRefrigHealthPct")
            .outcome("excursionFlag")
            .treatmentForm("CONTINUOUS")
            .dataQuery(mapper.readValue(H3_QUERY, DenseQueryDto.class))
            .expectedRowCount(563_028)
            .stripColumns(List.of("shipmentId", "dispatchTimestamp"))
            .dagEdges(H3_DAG_EDGES)
            .dsepThreshold(0.044)
            .adjustmentSet(H3_W_COLUMNS)
            .mediatorsExcluded(mapper.readValue(H3_MEDIATORS_EXCLUDED, LIST_OF_MAPS))
            .estimationVariants(mapper.readValue(H3_ESTIMATION_VARIANTS, LIST_OF_MAPS))
            .gates(mapper.readValue(H3_GATES, MAP_TYPE))
            .mediation(mapper.readValue(H3_MEDIATION, LIST_OF_MAPS))
            .grfConfigs(mapper.readValue(H3_GRF_CONFIGS, LIST_OF_MAPS))
            .refutations(mapper.readValue(H3_REFUTATIONS, LIST_OF_MAPS))
            .sensitivity(mapper.readValue(H3_SENSITIVITY, MAP_TYPE))
            .structuralBreaks(mapper.readValue(H3_STRUCTURAL_BREAKS, LIST_OF_MAPS))
            .residualChecks(mapper.readValue(H3_RESIDUAL_CHECKS, MAP_TYPE))
            .rangeChecks(mapper.readValue(H3_RANGE_CHECKS, MAP_TYPE))
            .unmeasuredConfounding(mapper.readValue(H3_UNMEASURED_CONFOUNDING, LIST_OF_MAPS))
            .externalization(mapper.readValue(H3_EXTERNALIZATION, MAP_TYPE))
            .discrepancyLog(mapper.readValue(H3_DISCREPANCY_LOG, LIST_OF_MAPS))
            .build();
    }
}
