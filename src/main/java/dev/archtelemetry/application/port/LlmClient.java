package dev.archtelemetry.application.port;

@FunctionalInterface
public interface LlmClient {
    String query(String systemPrompt, String userMessage);
}
