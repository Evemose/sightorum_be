package com.rorm.ml.restate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@Slf4j
public class RestateDeploymentRegistrar {

    private final RestClient restateAdminClient;
    private final String endpointUrl;

    public RestateDeploymentRegistrar(RestClient restateAdminClient, String endpointUrl) {
        this.restateAdminClient = restateAdminClient;
        this.endpointUrl = endpointUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerDeployment() {
        log.info("Registering Restate deployment at {}", endpointUrl);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                restateAdminClient.post()
                    .uri("/deployments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("uri", endpointUrl, "force", true))
                    .retrieve()
                    .toBodilessEntity();

                log.info("Restate deployment registered successfully");
                return;
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 409) {
                    log.info("Restate deployment already registered");
                    return;
                }
                throw e;
            } catch (Exception e) {
                log.warn("Restate registration attempt {}/3 failed: {}", attempt, e.getMessage());
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("Failed to register Restate deployment at {} after 3 attempts", endpointUrl);
    }
}
