package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
class VerificationResponseFormatter {

    private final ObjectMapper objectMapper;

    String format(String pattern, double generatorNumber, double skepticNumber,
                  String verdict, boolean material, String executorNote,
                  Map<String, Object> evidence) {
        try {
            var response = new VerificationResponse(true, pattern, generatorNumber, skepticNumber,
                skepticNumber - generatorNumber, verdict, material, executorNote, evidence, null);
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return error("Failed to format verification: " + e.getMessage());
        }
    }

    String error(String message) {
        try {
            return objectMapper.writeValueAsString(
                new VerificationResponse(false, null, 0, 0, 0, null, false, null, null, message));
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    public record VerificationResponse(
        boolean success,
        @Nullable String pattern,
        double generatorNumber,
        double skepticNumber,
        double delta,
        @Nullable String verdict,
        boolean material,
        @Nullable String executorNote,
        @Nullable Map<String, Object> evidence,
        @Nullable String error
    ) {}
}
