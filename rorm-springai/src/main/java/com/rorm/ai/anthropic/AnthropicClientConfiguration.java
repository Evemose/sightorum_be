package com.rorm.ai.anthropic;

import com.anthropic.bedrock.backends.BedrockBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.chat.JsonbChatMemoryRepository;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.regions.Region;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(TokenThrottleProperties.class)
class AnthropicClientConfiguration {

    @Bean
    @DirectApi
    AnthropicClient directApiClient(
        @Value("${anthropic.api-key}") String apiKey,
        @Value("${anthropic.base-url:}") String baseUrl
    ) {
        var builder = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofMinutes(30));
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    @Bean
    @BedrockApi
    @ConditionalOnProperty("anthropic.bedrock.region")
    AnthropicClient bedrockClient(
        @Value("${anthropic.bedrock.region}") String region,
        @Value("${anthropic.bedrock.api-key:}") String apiKey
    ) {
        var builder = BedrockBackend.builder();
        if (apiKey.isBlank()) {
            builder.fromEnv();
        } else {
            builder.apiKey(apiKey);
        }
        return AnthropicOkHttpClient.builder()
            .backend(builder.region(Region.of(region)).build())
            .timeout(Duration.ofMinutes(60))
            .build();
    }

    @Bean
    @BedrockApi
    @ConditionalOnMissingBean(annotation = BedrockApi.class)
    AnthropicClient bedrockFallback(@DirectApi AnthropicClient directClient) {
        return directClient;
    }

    @Bean
    TokenThrottle tokenThrottle(TokenThrottleProperties properties) {
        return new TokenThrottle(properties);
    }

    @Bean
    ChatModel chatModel(
        @DirectApi AnthropicClient directClient,
        @BedrockApi AnthropicClient bedrockClient,
        AnthropicParamsBuilder paramsBuilder,
        TokenThrottle throttle,
        ModelFallbackPolicy fallbackPolicy
    ) {
        return new JournaledAnthropicChatModel(
            directClient, bedrockClient, paramsBuilder, throttle,
            ObservationRegistry.NOOP, fallbackPolicy);
    }

    @Bean
    ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }

    @Bean
    ChatMemoryRepository chatMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JsonbChatMemoryRepository(jdbcTemplate, objectMapper);
    }
}
