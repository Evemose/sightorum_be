package com.rorm.ml.restate;

import dev.restate.admin.api.DeploymentApi;
import dev.restate.admin.client.ApiClient;
import dev.restate.admin.client.ApiException;
import dev.restate.admin.model.RegisterDeploymentRequest;
import dev.restate.admin.model.RegisterDeploymentRequestAnyOf;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.net.InetAddress;

/**
 * Auto-registers the Restate HTTP endpoint with the Restate server on application startup.
 * Uses the Restate admin SDK ({@link DeploymentApi}) for type-safe registration.
 */
@Slf4j
public class RestateDeploymentRegistrar {

    private final DeploymentApi deploymentApi;
    private final int endpointPort;

    public RestateDeploymentRegistrar(ApiClient apiClient, int endpointPort) {
        this.deploymentApi = new DeploymentApi(apiClient);
        this.endpointPort = endpointPort;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerDeployment() {
        var endpointUri = resolveEndpointUri();
        log.info("Registering Restate deployment at {}", endpointUri);

        try {
            var request = new RegisterDeploymentRequest(
                new RegisterDeploymentRequestAnyOf().uri(endpointUri).force(true)
            );
            var response = deploymentApi.createDeployment(request);
            log.info("Restate deployment registered: {} service(s)", response.getServices().size());
        } catch (ApiException e) {
            log.warn("Failed to register Restate deployment at {} (server may not be running): {} {}",
                endpointUri, e.getCode(), e.getMessage());
        }
    }

    private String resolveEndpointUri() {
        try {
            var hostname = InetAddress.getLocalHost().getHostAddress();
            return "http://" + hostname + ":" + endpointPort;
        } catch (Exception e) {
            return "http://localhost:" + endpointPort;
        }
    }
}
