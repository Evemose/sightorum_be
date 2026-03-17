package com.rorm.ml.restate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Slf4j
public class RestateDeploymentRegistrar {

    private final String adminUrl;
    private final String endpointHost;
    private final int endpointPort;

    public RestateDeploymentRegistrar(String adminUrl, String endpointHost, int endpointPort) {
        this.adminUrl = adminUrl;
        this.endpointHost = endpointHost;
        this.endpointPort = endpointPort;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerDeployment() {
        var endpointUri = "http://" + endpointHost + ":" + endpointPort;
        log.info("Registering Restate deployment at {} via admin {}", endpointUri, adminUrl);

        var body = """
            {"uri": "%s", "force": true}""".formatted(endpointUri);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(adminUrl + "/deployments"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("Restate deployment registered (HTTP {})", response.statusCode());
                    return;
                } else {
                    log.warn("Restate registration attempt {}/3 returned HTTP {}: {}",
                        attempt, response.statusCode(), response.body());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
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
        log.warn("Failed to register Restate deployment at {} after 3 attempts", endpointUri);
    }
}
