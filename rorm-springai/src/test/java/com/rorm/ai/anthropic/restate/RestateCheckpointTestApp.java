package com.rorm.ai.anthropic.restate;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.anthropic.JournaledAnthropicChatModel;
import com.rorm.ai.anthropic.ScriptedAnthropicClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication(scanBasePackages = "com.rorm.ai.anthropic.restate")
@dev.restate.sdk.springboot.EnableRestate
public class RestateCheckpointTestApp {

    static final AtomicInteger LLM_CALL_COUNT = new AtomicInteger();
    @Value("${restate.admin.url}")
    String adminUrl;
    @Value("${restate.sdk.http.port}")
    int sdkPort;

    static void main(String[] args) {
        SpringApplication.run(RestateCheckpointTestApp.class, args);
    }

    @Bean
    AnthropicClient anthropicClient() {
        return ScriptedAnthropicClient.withCallCounter(LLM_CALL_COUNT);
    }

    @Bean
    ChatModel chatModel(AnthropicClient client, ObjectMapper mapper) {
        return new JournaledAnthropicChatModel(client, mapper, null, null);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() throws Exception {
        var endpointUri = "http://host.docker.internal:" + sdkPort;
        System.out.println("Registering with Restate admin at " + adminUrl + " endpoint=" + endpointUri);

        var body = "{\"uri\":\"%s\",\"force\":true}".formatted(endpointUri);
        for (int attempt = 0; attempt < 5; attempt++) {
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(
                    HttpRequest.newBuilder()
                        .uri(java.net.URI.create(adminUrl + "/deployments"))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .build(),
                    BodyHandlers.ofString());
                System.out.println("Registration status: " + response.statusCode()
                                   + " body: " + response.body());
                System.out.println("READY");
                System.out.flush();
                return;
            } catch (Exception e) {
                System.out.println("Registration attempt " + attempt + " failed: " + e.getMessage());
                Thread.sleep(2000);
            }
        }
        throw new IllegalStateException("Failed to register with Restate after 5 attempts");
    }
}
