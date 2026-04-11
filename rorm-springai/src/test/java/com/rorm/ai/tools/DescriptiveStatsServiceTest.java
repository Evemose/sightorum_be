package com.rorm.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DescriptiveStatsServiceTest {

    private DescriptiveStatsService service;
    private MockRestServiceServer mockServer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://stub");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        var restClient = builder.build();
        service = new DescriptiveStatsService(restClient);
        objectMapper = new ObjectMapper();
    }

    @Test
    void distributionShapeParsesScipyResponse() throws Exception {
        var responseBody = """
            {
              "n": 120,
              "skew": 2.3,
              "excess_kurtosis": 4.1,
              "dip_p": 0.02,
              "is_multimodal": true,
              "mode_count": 2,
              "mode_separation_ratio": 1.4,
              "notes": []
            }
            """;
        mockServer.expect(requestTo("http://stub/descriptive/distribution-shape"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""
                {"values":[1.0,2.0,3.0,4.0],"run_dip_test":true}
                """))
            .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var result = service.distributionShape(List.of(1.0, 2.0, 3.0, 4.0));

        assertThat(result.n()).isEqualTo(120);
        assertThat(result.skew()).isEqualTo(2.3);
        assertThat(result.excessKurtosis()).isEqualTo(4.1);
        assertThat(result.dipP()).isEqualTo(0.02);
        assertThat(result.isMultimodal()).isTrue();
        assertThat(result.modeCount()).isEqualTo(2);
        assertThat(result.modeSeparationRatio()).isEqualTo(1.4);
        mockServer.verify();
    }

    @Test
    void seriesAnalysisParsesStlAutocorrChangepointResponse() {
        var responseBody = """
            {
              "n": 24,
              "stl": {
                "f_t": 0.85,
                "f_s": 0.62,
                "trend": [1.0, 2.0, 3.0],
                "seasonal": [0.1, -0.1, 0.0],
                "remainder": [0.01, -0.01, 0.0]
              },
              "autocorr_peak": {"peak_lag": 7, "peak_value": 0.92},
              "changepoints": [12, 18],
              "notes": ["interpolated"]
            }
            """;
        mockServer.expect(requestTo("http://stub/descriptive/series-analysis"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var result = service.seriesAnalysis(
            List.of(1.0, 2.0, 3.0, 4.0, 5.0),
            7, true, true, true, 5.0
        );

        assertThat(result.n()).isEqualTo(24);
        assertThat(result.stl()).isNotNull();
        assertThat(result.stl().fT()).isEqualTo(0.85);
        assertThat(result.stl().fS()).isEqualTo(0.62);
        assertThat(result.autocorrPeak()).isNotNull();
        assertThat(result.autocorrPeak().peakLag()).isEqualTo(7);
        assertThat(result.changepoints()).containsExactly(12, 18);
        assertThat(result.notes()).containsExactly("interpolated");
        mockServer.verify();
    }

    @Test
    void pythonErrorWrappedAsDescriptiveStatsException() {
        mockServer.expect(requestTo("http://stub/descriptive/distribution-shape"))
            .andRespond(withServerError());

        assertThatThrownBy(() -> service.distributionShape(List.of(1.0, 2.0)))
            .isInstanceOf(DescriptiveStatsService.DescriptiveStatsException.class)
            .hasMessageContaining("distribution-shape call failed");
    }
}
