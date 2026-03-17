package com.rorm.ml.restate;

import com.rorm.durable.DurableRuntime;
import com.rorm.ml.RormMlProperties;
import dev.restate.client.Client;
import dev.restate.sdk.springboot.EnableRestate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRestate
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "true")
public class RestateConfiguration {

    @Bean
    public DurableRuntime restateDurableRuntime(Client restateClient) {
        return new RestateDurableRuntime(restateClient);
    }

    @Bean
    public RestateDeploymentRegistrar restateDeploymentRegistrar(RormMlProperties properties) {
        return new RestateDeploymentRegistrar(
            properties.restateAdminUrl(),
            properties.restateEndpointHost(),
            properties.restateEndpointPort()
        );
    }
}
