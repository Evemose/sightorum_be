package com.rorm.ai.swarm;

import com.rorm.ai.anthropic.restate.RestateCheckpointTestApp;
import dev.restate.sdk.springboot.EnableRestate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test entrypoint that boots a real Spring context scanning the packages
 * available to the AI module. Used by {@link DurableSwarmRestateDurabilityTest}
 * as the subprocess main class.
 */
@SpringBootApplication(scanBasePackages = {"com.rorm"})
@ComponentScan(
    basePackages = {"com.rorm"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {RestateCheckpointTestApp.class, DurableSwarmE2ETest.TestApp.class}
    )
)
@Import(DurableSwarmRestateTestApp.class)
@ConfigurationPropertiesScan(basePackages = {"com.rorm"})
@EnableJpaRepositories(basePackages = "com.rorm")
@EntityScan(basePackages = "com.rorm")
@EnableRestate
public class DurableSwarmTestApplication {

    static void main(String[] args) {
        SpringApplication.run(DurableSwarmTestApplication.class, args);
    }
}
