package com.rorm.ml.peristence;

import com.rorm.ml.dto.ShapJobRequest;
import com.rorm.ml.dto.StabilitySelectionJobRequest;
import com.rorm.ml.dto.TrainingJobRequest;
import com.rorm.ml.dto.TuningJobRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
interface MLMapper {

    // ===== Training =====

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "trainingRequest.modelSpec", source = "req.modelConfig")
    Training toEntity(TrainingJobRequest req, String schema, UUID trainingId);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "trainingRequest.modelSpec", source = "req.paramSpace")
    Training toEntity(TuningJobRequest req, String schema, UUID trainingId);

    @Mapping(target = "jobId", source = "trainingId")
    @Mapping(target = "reason", source = "trainingRequest.reason")
    @Mapping(target = "furtherInstructions", source = "trainingRequest.furtherInstructions")
    MLJobInfo toInfo(Training training);

    // ===== Stability Selection =====

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "request", source = "req")
    StabilitySelectionRun toEntity(StabilitySelectionJobRequest req, String schema, UUID analysisId);

    default PersistentStabilitySelectionRequest toPersistent(StabilitySelectionJobRequest req) {
        return new PersistentStabilitySelectionRequest(
            req.reason(),
            req.datasource().sql(),
            req.targetColumn(),
            req.problemType(),
            req.featureColumns() != null ? req.featureColumns() : List.of(),
            req.bootstrapRuns(),
            req.sampleFraction(),
            req.correlationThreshold(),
            req.polynomialDegree(),
            req.selectionTopK()
        );
    }

    @Mapping(target = "jobId", source = "analysisId")
    @Mapping(target = "reason", source = "request.reason")
    @Mapping(target = "furtherInstructions", ignore = true)
    MLJobInfo toInfo(StabilitySelectionRun run);

    // ===== SHAP =====

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "request", source = "req")
    ShapRun toEntity(ShapJobRequest req, UUID analysisId);

    default PersistentShapRequest toPersistent(ShapJobRequest req) {
        return new PersistentShapRequest(
            req.reason(),
            req.runId(),
            req.features(),
            req.nBins(),
            req.nBreakpoints()
        );
    }

    @Mapping(target = "jobId", source = "analysisId")
    @Mapping(target = "reason", source = "request.reason")
    @Mapping(target = "furtherInstructions", constant = "Interpret the SHAP dependence curves: identify thresholds, non-linearities, and actionable insights.")
    MLJobInfo toInfo(ShapRun run);

}
