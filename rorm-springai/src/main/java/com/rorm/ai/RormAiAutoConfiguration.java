package com.rorm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.chat.*;
import com.rorm.ai.chat.node.ChatNodeRepository;
import com.rorm.ai.swarm.SwarmConfig;
import com.rorm.ai.tools.DataOverviewTool;
import com.rorm.ai.tools.QueryExecutionTool;
import com.rorm.engine.ExpressionTypeResolver;
import com.rorm.fetcher.Fetcher;
import com.rorm.mapper.ExpressionMapper;
import com.rorm.mapper.QueryMapper;
import com.rorm.misc.YamlPropertySource;
import com.rorm.ml.tools.MlTrainingTool;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableJpaAuditing
@AutoConfiguration(after = ChatMemoryAutoConfiguration.class)
@ConditionalOnClass(ChatModel.class)
@EnableConfigurationProperties({
    RormAiProperties.class,
    SwarmConfig.class
})
@YamlPropertySource("classpath:application-ai.yaml")
// TODO: EntityScan and JpaRepositories kept for ML module compatibility - remove when ML is refactored
@EntityScan(basePackageClasses = {ChatProgress.class})
@EnableJpaRepositories(basePackageClasses = {ChatProgressRepository.class})
public class RormAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataOverviewService dataOverviewService(ExpressionTypeResolver typeResolver) {
        return new DataOverviewService(typeResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryExecutionTool queryExecutionTool(
        Fetcher fetcher,
        ObjectMapper objectMapper,
        RormAiProperties properties,
        QueryMapper queryMapper
    ) {
        return new QueryExecutionTool(fetcher, objectMapper, properties, queryMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DataOverviewTool dataOverviewTool(
        DataOverviewService dataOverviewService,
        ExpressionTypeResolver typeResolver,
        ExpressionMapper expressionMapper,
        Fetcher fetcher,
        ObjectMapper objectMapper,
        RormAiProperties properties
    ) {
        return new DataOverviewTool(
            dataOverviewService,
            typeResolver,
            expressionMapper,
            fetcher,
            objectMapper,
            properties
        );
    }

    // TODO: TrainingEventsSupport and ChatForkService kept for ML module compatibility - remove when ML is refactored
    @Bean
    @ConditionalOnMissingBean
    public TrainingEventsSupport chatResumeService(
        ChatProgressRepository chatProgressRepository,
        ChatNodeRepository chatNodeRepository,
        ObjectMapper objectMapper,
        AiChatService aiChatService
    ) {
        return new TrainingEventsSupport(
            chatProgressRepository,
            chatNodeRepository,
            objectMapper,
            aiChatService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatForkService chatForkService(
        ChatProgressRepository chatProgressRepository,
        ObjectMapper objectMapper
    ) {
        return new ChatForkService(chatProgressRepository, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiChatService rormAiService(
        ChatModel chatModel,
        QueryExecutionTool queryExecutionTool,
        DataOverviewTool dataOverviewTool,
        MlTrainingTool mlTrainingTool,
        ChatMemory chatMemory,
        RormAiProperties properties
    ) {
        return new AiChatService(
            chatModel,
            queryExecutionTool,
            dataOverviewTool,
            mlTrainingTool,
            chatMemory,
            properties
        );
    }

}
