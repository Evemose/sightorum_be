package com.rorm.ml.restate;

import com.rorm.ml.*;
import com.rorm.ml.peristence.MLJobMetadataStore;
import com.rorm.ml.stream.JobCompletionHandler;
import dev.restate.admin.client.ApiClient;
import dev.restate.client.Client;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Restate-mode bean wiring. Active only when {@code rorm.ml.durable-execution=true}.
 * <p>
 * The Restate Spring Boot starter auto-configures:
 * <ul>
 *   <li>{@link Client} bean (via {@code restate.client.base-uri})</li>
 *   <li>HTTP endpoint for Restate server callbacks (via {@code restate.sdk.http.port})</li>
 *   <li>Auto-discovery of {@code @RestateWorkflow} beans</li>
 *   <li>Jackson serialization using Spring's ObjectMapper</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "true")
public class RestateConfiguration {

    @Bean
    public AwakeableRegistry awakeableRegistry() {
        return new AwakeableRegistry();
    }

    @Bean
    public JobCompletionHandler restateJobCompletionHandler(
        AwakeableRegistry registry, Client restateClient
    ) {
        return new RestateJobCompletionHandler(registry, restateClient);
    }

    @Bean
    public AsyncJobGateway restateAsyncJobGateway(
        MLJobMetadataStore metadataStore,
        MlTrainingService mlTrainingService, Client restateClient
    ) {
        return new RestateAsyncJobGateway(metadataStore, mlTrainingService, restateClient);
    }

    @Bean
    public JobResultAwaiter restateJobResultAwaiter(Client restateClient) {
        return new RestateJobResultAwaiter(restateClient);
    }

    @Bean
    public JobControl restateJobControl(
        AwakeableRegistry registry, Client restateClient,
        MLJobMetadataStore metadataStore, AsyncJobGateway jobGateway
    ) {
        return new RestateJobControl(registry, restateClient, metadataStore, jobGateway);
    }

    @Bean
    public ApiClient restateAdminApiClient(RormMlProperties properties) {
        var apiClient = new ApiClient();
        apiClient.setBasePath(properties.restateAdminUrl());
        return apiClient;
    }

    @Bean
    public RestateDeploymentRegistrar restateDeploymentRegistrar(
        ApiClient restateAdminApiClient, RormMlProperties properties
    ) {
        return new RestateDeploymentRegistrar(restateAdminApiClient, properties.restateEndpointPort());
    }
}
