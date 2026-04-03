package com.rorm.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.chat.JsonbChatMemoryRepository;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(TokenThrottleProperties.class)
class AnthropicClientConfiguration {

    @Bean
    AnthropicClient anthropicClient(@Value("${anthropic.api-key}") String apiKey) {
        return AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofMinutes(30))
            .build();
    }

    @Bean
    TokenThrottle tokenThrottle(TokenThrottleProperties properties) {
        return new TokenThrottle(properties);
    }

    @Bean
    ChatModel chatModel(
        AnthropicClient client,
        AnthropicParamsBuilder paramsBuilder,
        TokenThrottle throttle
    ) {
        return new JournaledAnthropicChatModel(client, paramsBuilder, throttle, ObservationRegistry.NOOP);
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
