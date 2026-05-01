package com.rorm.ml.distribution;

import com.rorm.ml.MlTrainingService;
import com.rorm.ml.dto.DatasourceConfig;
import com.rorm.ml.dto.StabilitySelectionJobRequest;
import com.rorm.ml.dto.TrainingJobRequest;
import com.rorm.ml.dto.model.train.LinearRegressionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoConsumer;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// requires docker-ml-multi.yml
@SpringBootTest(classes = MultiRegionDistributionIT.TestApp.class)
@ActiveProfiles("multi-region-it")
@EnabledIfSystemProperty(named = "multi-region-it", matches = "true")
@DisplayName("Multi-region distribution IT")
class MultiRegionDistributionIT {

    private static final String REQUESTS_STREAM = "ml_training:training_requests";
    private static final String WORKERS_GROUP = "training_workers";
    private static final String RESULTS_STREAM = "ml_training:training_results";
    private static final Duration WORKER_DISCOVERY_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration RESULT_TIMEOUT = Duration.ofMinutes(5);

    @Autowired
    private MlTrainingService mlTrainingService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("jobs fan out across us-east-1 and eu-west-1 workers and produce results")
    void jobsDistributeAcrossRegions() throws Exception {
        var trainingIds = List.of(
            submitTraining(),
            submitTraining(),
            submitTraining()
        );
        var analysisId = submitStabilitySelection();

        assertThat(trainingIds).doesNotContainNull();
        assertThat(analysisId).isNotNull();

        var deadline = Instant.now().plus(WORKER_DISCOVERY_TIMEOUT);
        Set<String> consumerNames = Set.of();
        while (Instant.now().isBefore(deadline)) {
            consumerNames = listConsumerNames();
            if (containsRegion(consumerNames, "us-east-1") && containsRegion(consumerNames, "eu-west-1")) {
                break;
            }
            Thread.sleep(2_000);
        }

        assertThat(consumerNames)
            .as("expected consumers from both regions in %s", WORKERS_GROUP)
            .anyMatch(n -> n.contains("us-east-1"))
            .anyMatch(n -> n.contains("eu-west-1"));

        var resultArrived = awaitFirstResult(RESULT_TIMEOUT);
        assertThat(resultArrived)
            .as("at least one job event should land on %s", RESULTS_STREAM)
            .isTrue();
    }

    private UUID submitTraining() {
        var request = TrainingJobRequest.builder()
            .datasource(syntheticDatasource(1_000))
            .targetColumn("y")
            .featureColumns(List.of("x1", "x2"))
            .modelConfig(new LinearRegressionConfig(true))
            .build();
        return mlTrainingService.submitTraining(request).trainingId();
    }

    private UUID submitStabilitySelection() {
        var request = StabilitySelectionJobRequest.builder()
            .datasource(syntheticDatasource(500))
            .targetColumn("y")
            .featureColumns(List.of("x1", "x2"))
            .bootstrapRuns(10)
            .sampleFraction(0.7)
            .correlationThreshold(0.9)
            .randomState(42)
            .build();
        return mlTrainingService.submitStabilitySelection(request).analysisId();
    }

    private Set<String> listConsumerNames() {
        try {
            var consumers = redisTemplate.opsForStream().consumers(REQUESTS_STREAM, WORKERS_GROUP);
            return consumers.stream().map(XInfoConsumer::consumerName).collect(Collectors.toSet());
        } catch (Exception _) {
            return Set.of();
        }
    }

    private boolean containsRegion(Set<String> names, String region) {
        return names.stream().anyMatch(n -> n.contains(region));
    }

    private boolean awaitFirstResult(Duration timeout) {
        var deadline = Instant.now().plus(timeout);
        var readOpts = StreamReadOptions.empty().count(1).block(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            var batch = redisTemplate.opsForStream().read(
                readOpts, StreamOffset.create(RESULTS_STREAM, ReadOffset.from("0")));
            if (batch != null && !batch.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private DatasourceConfig syntheticDatasource(int rows) {
        return new DatasourceConfig(
            "SELECT generate_series(1, :rows) AS id, random() AS x1, random() AS x2, random() AS y",
            Map.of("rows", rows)
        );
    }

    @SpringBootApplication(scanBasePackages = "com.rorm.ml")
    static class TestApp {
        static void main(String[] args) {
            SpringApplication.run(TestApp.class, args);
        }
    }
}
