package com.rorm.ai.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Slf4j
@Component
public class DescriptiveStatsService {

    private final RestClient restClient;

    public DescriptiveStatsService(@Qualifier("mlRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public DistributionShape distributionShape(List<Double> values) {
        var request = new DistributionShapeRequest(values, true);
        try {
            var response = restClient.post()
                .uri("/descriptive/distribution-shape")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(DistributionShape.class);
            if (response == null) {
                throw new DescriptiveStatsException("Empty response from /descriptive/distribution-shape");
            }
            return response;
        } catch (RestClientException e) {
            throw new DescriptiveStatsException("distribution-shape call failed: " + e.getMessage(), e);
        }
    }

    public SeriesAnalysis seriesAnalysis(List<Double> values, @Nullable Integer period,
                                         boolean runStl, boolean runAutocorr,
                                         boolean runChangepoint, double peltPenalty) {
        var request = new SeriesAnalysisRequest(values, period, runStl, runAutocorr, runChangepoint, peltPenalty);
        try {
            var response = restClient.post()
                .uri("/descriptive/series-analysis")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(SeriesAnalysis.class);
            if (response == null) {
                throw new DescriptiveStatsException("Empty response from /descriptive/series-analysis");
            }
            return response;
        } catch (RestClientException e) {
            throw new DescriptiveStatsException("series-analysis call failed: " + e.getMessage(), e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DistributionShapeRequest(
        List<Double> values,
        @JsonProperty("run_dip_test") boolean runDipTest
    ) {}

    public record DistributionShape(
        int n,
        double skew,
        @JsonProperty("excess_kurtosis") double excessKurtosis,
        @Nullable @JsonProperty("dip_p") Double dipP,
        @JsonProperty("is_multimodal") boolean isMultimodal,
        @JsonProperty("mode_count") int modeCount,
        @JsonProperty("mode_separation_ratio") double modeSeparationRatio,
        List<String> notes
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SeriesAnalysisRequest(
        List<Double> values,
        @Nullable Integer period,
        @JsonProperty("run_stl") boolean runStl,
        @JsonProperty("run_autocorr") boolean runAutocorr,
        @JsonProperty("run_changepoint") boolean runChangepoint,
        @JsonProperty("pelt_penalty") double peltPenalty
    ) {}

    public record SeriesAnalysis(
        int n,
        @Nullable StlResult stl,
        @Nullable @JsonProperty("autocorr_peak") AutocorrPeak autocorrPeak,
        @Nullable List<Integer> changepoints,
        List<String> notes
    ) {}

    public record StlResult(
        @JsonProperty("f_t") double fT,
        @JsonProperty("f_s") double fS,
        List<Double> trend,
        List<Double> seasonal,
        List<Double> remainder
    ) {}

    public record AutocorrPeak(
        @JsonProperty("peak_lag") int peakLag,
        @JsonProperty("peak_value") double peakValue
    ) {}

    public static class DescriptiveStatsException extends RuntimeException {
        public DescriptiveStatsException(String message) {
            super(message);
        }

        public DescriptiveStatsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
