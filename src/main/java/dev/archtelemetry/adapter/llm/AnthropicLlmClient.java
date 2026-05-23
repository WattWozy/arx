package dev.archtelemetry.adapter.llm;

import dev.archtelemetry.application.port.LlmClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class AnthropicLlmClient implements LlmClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String DEFAULT_MODEL = "claude-haiku-4-5-20251001";
    private static final int MAX_TOKENS = 2048;

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    public AnthropicLlmClient(String apiKey) {
        this(apiKey, System.getenv().getOrDefault("ARX_MODEL", DEFAULT_MODEL));
    }

    public AnthropicLlmClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String query(String systemPrompt, String userMessage) {
        String body = buildRequestBody(systemPrompt, userMessage);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + response.body());
            }
            return extractText(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("LLM request failed", e);
        }
    }

    private String buildRequestBody(String systemPrompt, String userMessage) {
        return "{\"model\":" + jsonString(model)
                + ",\"max_tokens\":" + MAX_TOKENS
                + ",\"system\":" + jsonString(systemPrompt)
                + ",\"messages\":[{\"role\":\"user\",\"content\":" + jsonString(userMessage) + "}]}";
    }

    // Extract text field from the first content block in the Anthropic response
    private static String extractText(String json) {
        int textIdx = json.indexOf("\"text\":");
        if (textIdx < 0) return json;
        int start = json.indexOf('"', textIdx + 7);
        if (start < 0) return json;
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case 'n' -> { sb.append('\n'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    default -> sb.append(c);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
