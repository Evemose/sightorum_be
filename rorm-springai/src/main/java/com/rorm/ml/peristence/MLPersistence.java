package com.rorm.ml.peristence;

import com.rorm.ml.dto.ShapJobRequest;
import com.rorm.ml.dto.StabilitySelectionJobRequest;
import com.rorm.ml.dto.TrainingJobRequest;
import com.rorm.ml.dto.TuningJobRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MLPersistence {

    private final MLMapper mapper;
    private final TrainingRepository trainingRepo;
    private final StabilitySelectionRepository stabilityRepo;
    private final ShapRepository shapRepo;

    public void save(TrainingJobRequest req, String schema, UUID trainingId) {
        trainingRepo.save(mapper.toEntity(req, schema, trainingId));
    }

    public void save(TuningJobRequest req, String schema, UUID trainingId) {
        trainingRepo.save(mapper.toEntity(req, schema, trainingId));
    }

    public void save(StabilitySelectionJobRequest req, String schema, UUID analysisId) {
        stabilityRepo.save(mapper.toEntity(req, schema, analysisId));
    }

    public void save(ShapJobRequest req, UUID analysisId) {
        shapRepo.save(mapper.toEntity(req, analysisId));
    }

    public Optional<MLJobInfo> findByJobId(UUID jobId) {
        return trainingRepo.findByTrainingId(jobId).map(mapper::toInfo)
            .or(() -> stabilityRepo.findByAnalysisId(jobId).map(mapper::toInfo))
            .or(() -> shapRepo.findByAnalysisId(jobId).map(mapper::toInfo));
    }
}
