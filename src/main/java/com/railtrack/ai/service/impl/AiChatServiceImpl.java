package com.railtrack.ai.service.impl;

import com.railtrack.ai.prompt.PromptBuilder;
import com.railtrack.ai.service.AiChatService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class AiChatServiceImpl implements AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatServiceImpl.class);
    private static final String FALLBACK_MESSAGE =
            "Sorry, I couldn't process your request right now. Please try again later.";

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final double temperature;

    public AiChatServiceImpl(
            WebClient.Builder webClientBuilder,
            @Value("${groq.api.base-url}") String baseUrl,
            @Value("${groq.api.key}") String apiKey,
            @Value("${groq.api.model}") String model,
            @Value("${groq.api.temperature:0.7}") double temperature) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
    }

    @Override
    public String chat(String prompt) {
        return complete(PromptBuilder.buildAssistantPrompt(prompt));
    }

    @Override
    public String analyzeTrustedData(String prompt) {
        return complete(prompt);
    }

    private String complete(String prompt) {

        if (prompt == null || prompt.trim().isEmpty()) {
            return "Please enter your railway-related question.";
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Groq AI is unavailable because GROQ_API_KEY is not configured");
            return "AI assistance is not configured yet. Please add the GROQ_API_KEY environment variable.";
        }

        try {

            Map<String, Object> request = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", temperature
            );

            JsonNode response = webClient.post()
                    .uri("/chat/completions")
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(30));

            String content = response == null
                    ? null
                    : response.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                log.warn("Groq returned a response without message content");
                return FALLBACK_MESSAGE;
            }
            return content.trim();

        } catch (WebClientResponseException e) {
            log.error("Groq chat completion failed: status={}, response={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return FALLBACK_MESSAGE;
        } catch (Exception e) {
            log.error("Groq chat completion failed", e);
            return FALLBACK_MESSAGE;
        }
    }
}
