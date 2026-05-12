package com.rorm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.InMemorySwarmEventBus;
import com.rorm.ai.swarm.SwarmEventBus;
import com.rorm.ai.swarm.communication.InMemorySwarmContext;
import com.rorm.ai.swarm.communication.SwarmContext;
import com.rorm.ai.swarm.executor.CoalescingProperties;
import com.rorm.ai.swarm.knowledge.SwarmKnowledgeStore;
import com.rorm.ai.swarm.knowledge.VectorStoreSwarmKnowledgeStore;
import com.rorm.ai.tools.SwarmKnowledgeTool;
import com.rorm.misc.YamlPropertySource;
import com.rorm.ml.ConditionalOnInMemoryExecution;
import com.rorm.serialization.metamodel.ModelSpaceMixinModule;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@EnableConfigurationProperties({
    RormAiProperties.class,
    DurableSwarmConfig.class,
    CoalescingProperties.class
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
    SwarmContext swarmContext() {
        return new InMemorySwarmContext();
    }

    @Bean
    @ConditionalOnMissingBean(SwarmKnowledgeStore.class)
    SwarmKnowledgeStore swarmKnowledgeStore(VectorStore vectorStore) {
        return new VectorStoreSwarmKnowledgeStore(vectorStore);
    }

    @Bean
    @ConditionalOnBean(SwarmKnowledgeStore.class)
    SwarmKnowledgeTool swarmKnowledgeTool(SwarmKnowledgeStore store, ObjectMapper objectMapper) {
        return new SwarmKnowledgeTool(store, objectMapper);
    }

    @Bean
    ModelSpaceMixinModule modelSpaceMixinModule() {
        return new ModelSpaceMixinModule();
    }
}
