package com.rorm.ml;

import com.rorm.ml.dto.*;
import com.rorm.ml.exception.MlServiceException;
import lombok.RequiredArgsConstructor;
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
