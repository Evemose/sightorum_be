package com.rorm.client.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.ecs.EcsClient;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class MlServiceStartup implements SmartLifecycle {

    private final EcsClient ecs;
    private final RestClient rest = RestClient.create();
    @Value("${rorm.ml.cluster}")
    private String cluster;
    @Value("${rorm.ml.service}")
    private String service;
    @Value("${rorm.ml.service-base-url}")
    private String albUrl;
    @Value("${rorm.ml.startup.timeout:PT4M}")
    private Duration timeout;
    @Value("${rorm.ml.startup.poll-interval:PT3S}")
    private Duration pollInterval;
    @Value("${rorm.ml.startup.enabled:true}")
    private boolean enabled;
    private volatile boolean running;

    @Override
    public int getPhase() {
        // Run early — before anything that depends on ML service
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public void start() {
        if (!enabled) {
            running = true;
            return;
        }

        log.info("Ensuring ML service is running...");
        ensureRunning();
        log.info("ML service is healthy");
        running = true;
    }

    private void ensureRunning() {
        var desc = ecs.describeServices(r -> r
            .cluster(cluster)
            .services(service));

        var svc = desc.services().getFirst();
        if (svc.runningCount() > 0) {
            log.info("ML service already has {} running tasks", svc.runningCount());
            waitForHealthy();
            return;
        }

        log.info("No running tasks, setting desired count to 1...");
        ecs.updateService(r -> r
            .cluster(cluster)
            .service(service)
            .desiredCount(1));

        waitForHealthy();
    }

    private void waitForHealthy() {
        var deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            try {
                rest.get()
                    .uri(albUrl + "/health")
                    .retrieve()
                    .body(String.class);
                return;
            } catch (Exception e) {
                log.debug("ML service not ready yet: {}", e.getMessage());
            }

            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for ML service");
            }
        }

        throw new IllegalStateException(
            "ML service did not become healthy within " + timeout);
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}