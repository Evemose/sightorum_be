package com.rorm.ml.restate;

import com.rorm.durable.DurableJobRuntime;
import com.rorm.ml.RormMlProperties;
import com.rorm.ml.jobs.JobExecutor;
import dev.restate.admin.client.ApiClient;
import dev.restate.client.Client;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "true")
public class RestateConfiguration {

    @Bean
    public DurableJobRuntime restateDurableJobRuntime(Client restateClient) {
        return new RestateDurableJobRuntime(restateClient);
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
