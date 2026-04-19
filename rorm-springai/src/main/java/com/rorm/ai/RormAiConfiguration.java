package com.rorm.ai;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.InMemorySwarmEventBus;
import com.rorm.ai.swarm.SwarmEventBus;
import com.rorm.misc.YamlPropertySource;
import com.rorm.ml.ConditionalOnInMemoryExecution;
import com.rorm.serialization.metamodel.ModelSpaceMixinModule;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@EnableConfigurationProperties({
    RormAiProperties.class,
    DurableSwarmConfig.class
})
@EnableAspectJAutoProxy(proxyTargetClass = true)
@YamlPropertySource("classpath:application-ai.yaml")
public class RormAiConfiguration {

    @Bean
    @ConditionalOnInMemoryExecution
    SwarmEventBus swarmEventBus() {
        return new InMemorySwarmEventBus();
    }

    @Bean
    ModelSpaceMixinModule modelSpaceMixinModule() {
        return new ModelSpaceMixinModule();
    }
}
