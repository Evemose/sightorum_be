package com.rorm.ml;

import com.rorm.DurableFuture;
import com.rorm.StepJournal;
import com.rorm.ml.dto.*;
import com.rorm.ml.exception.MlServiceException;
import com.rorm.ml.stream.JobCompletionHandler;
import com.rorm.ml.stream.JobEvent;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MlTrainingService {

    @Qualifier("mlRestClient")
    private final RestClient restClient;
    private final JobCompletionHandler completionHandler;

    /**
     * Submit an async job and return a {@link DurableFuture} that completes
     * when the job finishes.
     * <p>
     * Uses the current {@link StepJournal} for journaling the HTTP submission
     * and creates an awakeable via {@link JobCompletionHandler} so the future
     * survives process crashes when running under Restate.
     */
    public DurableFuture<JobEvent> submit(AsyncJobRequest request) {
        var journal = StepJournal.current();

        var jobId = journal.run("ml:submit:" + request.jobType(), UUID.class, () ->
            switch (request) {
                case TrainingJobRequest r -> {
                    var resp = submitTraining(r);
                    if (resp.isNotAccepted()) {
                        throw new MlServiceException("Training not accepted: " + resp.message());
                    }
                    yield resp.trainingId();
                }
                case TuningJobRequest r -> {
                    var resp = submitTuningThenTraining(r);
                    if (resp.isNotAccepted()) {
                        throw new MlServiceException("Tuning not accepted: " + resp.message());
                    }
                    yield resp.trainingId();
                }
                case StabilitySelectionJobRequest r -> {
                    var resp = submitStabilitySelection(r);
                    if (resp.isNotAccepted()) {
                        throw new MlServiceException("Stability selection not accepted: " + resp.message());
                    }
                    yield resp.analysisId();
                }
                case ShapJobRequest r -> {
                    var resp = submitShapCurvesAsync(r);
                    if (resp.isNotAccepted()) {
                        throw new MlServiceException("SHAP not accepted: " + resp.message());
                    }
                    yield resp.analysisId();
                }
                case CausalVerificationJobRequest r -> {
                    var resp = submitCausalVerification(r);
                    if (resp.isNotAccepted()) {
                        throw new MlServiceException("Causal verification not accepted: " + resp.message());
                    }
                    yield resp.analysisId();
                }
            }
        );

        var future = journal.awakeable(JobEvent.class);
        completionHandler.register(jobId, future);
        return future;
    }

    public TrainingJobResponse submitTraining(TrainingJobRequest request) {
        try {
            return restClient.post()
                .uri("/train")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TrainingJobResponse.class);
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to submit training job", e);
        }
    }

    public TrainingJobResponse submitTuningThenTraining(TuningJobRequest request) {
        try {
            return restClient.post()
                .uri("/tune-and-train")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TrainingJobResponse.class);
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to submit tuning job", e);
        }
    }

    public PredictionResponse predict(UUID modelUuid, Object inputData) {
        try {
            return restClient.post()
                .uri("/predict/{modelUuid}", modelUuid)
                .contentType(MediaType.APPLICATION_JSON)
                .body(inputData)
                .retrieve()
                .body(PredictionResponse.class);
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to make prediction", e);
        }
    }

    public List<ModelInfo> listModels() {
        try {
            return restClient.get()
                .uri("/models/supervised")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to list models", e);
        }
    }

    public Optional<ModelInfo> getModelInfo(UUID modelUuid) {
        try {
            ModelInfo info = restClient.get()
                .uri("/models/supervised/{modelUuid}", modelUuid)
                .retrieve()
                .body(ModelInfo.class);
            return Optional.ofNullable(info);
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    public boolean deleteModel(UUID modelUuid) {
        try {
            Map<String, Object> response = restClient.delete()
                .uri("/models/supervised/{modelUuid}", modelUuid)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
            return response != null && Boolean.TRUE.equals(response.get("deleted"));
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to delete model", e);
        }
    }

    public List<Map<String, Object>> listAvailableModelTypes() {
        try {
            return restClient.get()
                .uri("/models")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to list available model types", e);
        }
    }

    public AsyncJobResponse submitStabilitySelection(StabilitySelectionJobRequest request) {
        try {
            return restClient.post()
                .uri("/analysis/stability-selection/async")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AsyncJobResponse.class);
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to submit stability selection", e);
        }
    }

    public List<Map<String, Object>> listStabilityRuns() {
        try {
            return restClient.get()
                .uri("/analysis/stability-selection/runs")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to list stability runs", e);
        }
    }

    public Optional<ShapCurvesResponse> getShapCurves(String runId, List<String> features, int nBins) {
        try {
            var uri = features != null && !features.isEmpty()
                ? "/analysis/stability-selection/{runId}/shap-curves?features={features}&n_bins={nBins}"
                : "/analysis/stability-selection/{runId}/shap-curves?n_bins={nBins}";

            var response = features != null && !features.isEmpty()
                ? restClient.get()
                .uri(uri, runId, String.join(",", features), nBins)
                .retrieve()
                .body(ShapCurvesResponse.class)
                : restClient.get()
                .uri(uri, runId, nBins)
                .retrieve()
                .body(ShapCurvesResponse.class);

            return Optional.ofNullable(response);
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    public AsyncJobResponse submitShapCurvesAsync(ShapJobRequest request) {
        try {
            var uriBuilder = new StringBuilder("/analysis/stability-selection/")
                .append(request.runId())
                .append("/shap-curves/async?n_bins=").append(request.nBins())
                .append("&n_breakpoints=").append(request.nBreakpoints());

            if (request.features() != null && !request.features().isEmpty()) {
                uriBuilder.append("&features=").append(String.join(",", request.features()));
            }

            return restClient.post()
                .uri(uriBuilder.toString())
                .retrieve()
                .body(AsyncJobResponse.class);
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to submit async SHAP computation", e);
        }
    }

    public AsyncJobResponse submitCausalVerification(CausalVerificationJobRequest request,
                                                     @Nullable String runId) {
        try {
            var uri = runId != null
                ? "/analysis/causal-verification/async?run_id={runId}"
                : "/analysis/causal-verification/async";
            var spec = runId != null
                ? restClient.post().uri(uri, runId)
                : restClient.post().uri(uri);
            return spec
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AsyncJobResponse.class);
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to submit causal verification", e);
        }
    }

    public AsyncJobResponse submitCausalVerification(CausalVerificationJobRequest request) {
        return submitCausalVerification(request, "b97ff236-b505-4b97-b098-405df85e23a6");
    }

    public Map<String, Object> validatePipelineSpec(CausalVerificationJobRequest request) {
        try {
            return restClient.post()
                .uri("/analysis/causal-verification/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to validate pipeline spec", e);
        }
    }

    public Map<String, Object> reexecutePipeline(String runId, Map<String, Object> specPatch) {
        try {
            return restClient.post()
                .uri("/analysis/causal-verification/runs/{runId}/reexecute", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(specPatch)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to re-execute pipeline run " + runId, e);
        }
    }

    public List<Map<String, Object>> listCausalRuns(int limit) {
        try {
            return restClient.get()
                .uri("/analysis/causal-verification/runs?limit={limit}", limit)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to list causal runs", e);
        }
    }

    public Map<String, Object> getCausalRun(String runId) {
        try {
            return restClient.get()
                .uri("/analysis/causal-verification/runs/{runId}", runId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientException e) {
            throw new MlServiceException("Failed to get causal run " + runId, e);
        }
    }

    public boolean isHealthy() {
        try {
            Map<String, Object> response = restClient.get()
                .uri("/health")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
            return response != null && "healthy".equals(response.get("status"));
        } catch (RestClientException e) {
            return false;
        }
    }
}
