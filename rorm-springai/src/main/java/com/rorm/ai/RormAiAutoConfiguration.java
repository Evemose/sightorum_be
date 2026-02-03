package com.rorm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.chat.ChatForkService;
import com.rorm.ai.chat.ChatProgressRepository;
import com.rorm.ai.chat.ChatResumeService;
import com.rorm.ai.chat.ProgressBasedChatMemoryRepository;
import com.rorm.ai.tools.DataOverviewTool;
import com.rorm.ai.tools.QueryExecutionTool;
import com.rorm.engine.ExpressionTypeResolver;
import com.rorm.fetcher.Fetcher;
import com.rorm.mapper.ExpressionMapper;
import com.rorm.mapper.QueryMapper;
import com.rorm.ml.tools.MlTrainingTool;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

@AutoConfiguration
@ConditionalOnClass(ChatModel.class)
@EnableConfigurationProperties(RormAiProperties.class)
@PropertySource("classpath:application-ai.properties")
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

    @Bean
    @ConditionalOnMissingBean
    public RormAiService rormAiService(
        ChatModel chatModel,
        QueryExecutionTool queryExecutionTool,
        DataOverviewTool dataOverviewTool,
        MlTrainingTool mlTrainingTool,
        ChatMemory chatMemory,
        ChatProgressRepository chatProgressRepository,
        RormAiProperties properties
    ) {
        return new RormAiService(
            chatModel,
            queryExecutionTool,
            dataOverviewTool,
            mlTrainingTool,
            chatMemory,
            chatProgressRepository,
            properties
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatResumeService chatResumeService(
        ChatProgressRepository chatProgressRepository,
        ObjectMapper objectMapper,
        RormAiService rormAiService
    ) {
        return new ChatResumeService(
            chatProgressRepository,
            objectMapper,
            rormAiService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatMemoryRepository chatMemoryRepository(
        ObjectMapper objectMapper,
        ChatProgressRepository chatProgressRepository
    ) {
        return new ProgressBasedChatMemoryRepository(objectMapper, chatProgressRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatForkService chatForkService(
        ChatProgressRepository chatProgressRepository,
        ObjectMapper objectMapper
    ) {
        return new ChatForkService(chatProgressRepository, objectMapper);
    }

}
