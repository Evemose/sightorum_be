package com.rorm.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;
    private final PipelineSpecConverter pipelineSpecConverter;

    /**
     * Submit an async job and return a {@link DurableFuture} that completes
     * when the job finishes.
     * <p>
     * The caller MUST pass the {@link StepJournal} captured on the
     * Restate-bound thread (typically {@code RormToolContext.stepJournal()}).
     * {@code StepJournal.current()} cannot be used here: tool callbacks
     * execute on reactor's boundedElastic threads where the
     * {@code ScopedValue} binding is gone, so the fallback would be the
     * in-memory journal whose awakeable ids are plain UUIDs — invalid
     * for Restate's {@code awakeableHandle.resolve}.
     */
    public DurableFuture<JobEvent> submit(AsyncJobRequest request, StepJournal journal) {

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
        return submitCausalVerification(request, null);
    }

    public ValidationResult validatePipelineSpec(CausalVerificationJobRequest request) {
        try {
            var bytes = restClient.post()
                .uri("/analysis/causal-verification/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM)
                .body(request)
                .retrieve()
                .onStatus(_ -> true, (_, _) -> { /* no-op, swallow errors so 4xx body still parses */ })
                .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new MlServiceException("Empty response from pipeline spec validation");
            }
            var firstNonWs = firstNonWhitespace(bytes);
            if (firstNonWs != '{' && firstNonWs != '[') {
                throw new MlServiceException("Pipeline spec validation returned non-JSON body: "
                                             + new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
            return objectMapper.readValue(bytes, ValidationResult.class);
        } catch (RestClientException | java.io.IOException e) {
            throw new MlServiceException("Failed to validate pipeline spec", e);
        }
    }

    private static int firstNonWhitespace(byte[] bytes) {
        for (var b : bytes) {
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                return b;
            }
        }
        return -1;
    }

    /**
     * Re-execute a base run with a partial spec change.
     * <p>
     * {@code baseSpec} is optional: when present it is sent in the
     * request body and Python uses it directly; when null Python
     * recovers the merged spec server-side by walking the base run's
     * lineage (parent_run_id + patch chain) back to a root. Either way
     * step-level pipeline checkpoints under
     * {@code causal_cp:{baseRunId}:{step}} are reused to skip
     * unaffected steps.
     */
    public DurableFuture<JobEvent> reexecuteWithBase(ReexecuteWithBaseRequest req,
                                                     com.rorm.ai.RormToolContext ctx) {
        var baseRunId = req.baseRunId();
        var baseSpec = req.baseSpec();
        var specPatch = req.specPatch();
        var modelSpace = ctx.modelSpace();
        var schema = ctx.schema();
        var journal = ctx.stepJournal();
        Map<String, Object> body;
        if (baseSpec != null) {
            var baseRequest = pipelineSpecConverter.convert(
                baseSpec, baseSpec.hypothesisId() + " causal verification reexec",
                modelSpace, schema);
            body = Map.of(
                "base_spec", baseRequest,
                "spec_patch", specPatch
            );
        } else {
            // No base spec in registry — Python recovers it via lineage walk.
            body = Map.of("spec_patch", specPatch);
        }
        var jobId = journal.run("ml:reexec:" + baseRunId, UUID.class, () -> {
            try {
                var resp = restClient.post()
                    .uri("/analysis/causal-verification/runs/{runId}/reexecute", baseRunId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(AsyncJobResponse.class);
                if (resp == null || resp.isNotAccepted()) {
                    throw new MlServiceException(
                        "Reexecute not accepted for base run " + baseRunId
                        + ": " + (resp == null ? "null response" : resp.message()));
                }
                return resp.analysisId();
            } catch (RestClientException e) {
                throw new MlServiceException("Failed to reexecute base run " + baseRunId, e);
            }
        });
        var future = journal.awakeable(JobEvent.class);
        completionHandler.register(jobId, future);
        return future;
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

    /**
     * Inputs to {@link #reexecuteWithBase(ReexecuteWithBaseRequest, com.rorm.ai.RormToolContext)}
     * bundling base-run identity, the optionally-recorded base spec, and
     * the partial patch the caller wants applied.
     * <p>
     * {@code baseSpec} may be {@code null} when the base run was itself
     * produced by reexecution (the registry only holds the patch in that
     * case); Python recovers the merged spec server-side via lineage
     * walk. {@code modelSpace} / {@code schema} come from the
     * {@link com.rorm.ai.RormToolContext} so they ride the same captured
     * tool-context that carries {@code stepJournal}.
     */
    public record ReexecuteWithBaseRequest(
        String baseRunId,
        @org.springframework.lang.Nullable PipelineSpecRequest baseSpec,
        PipelineSpecPatch specPatch
    ) {}
}
