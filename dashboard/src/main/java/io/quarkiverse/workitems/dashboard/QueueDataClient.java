package io.quarkiverse.workitems.dashboard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class QueueDataClient {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public QueueDataClient(final String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Fetch all WorkItems and parse their labels into WorkItemInfo records.
     * Returns empty list on any error (server not ready, network issue, etc.)
     */
    public List<WorkItemInfo> fetchWorkItems() {
        try {
            final var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/workitems"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            final var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();

            final JsonNode root = mapper.readTree(response.body());
            if (!root.isArray()) return List.of();

            final List<WorkItemInfo> items = new ArrayList<>();
            for (final JsonNode item : root) {
                final String id = item.path("id").asText("");
                final String title = item.path("title").asText("(no title)");
                final String priority = item.path("priority").asText("NORMAL");
                final List<String> labels = new ArrayList<>();
                for (final JsonNode label : item.path("labels")) {
                    labels.add(label.path("path").asText(""));
                }
                items.add(new WorkItemInfo(id, title, priority, labels));
            }
            return items;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Trigger the document review scenario asynchronously.
     * Returns a CompletableFuture so the caller is not blocked.
     */
    public CompletableFuture<String> runDocumentReviewScenario() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                final var request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/queue-examples/review/run?delay=1500"))
                        .timeout(Duration.ofMinutes(2))
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                final var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 ? "Scenario complete" : "Error: HTTP " + response.statusCode();
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        });
    }

    public boolean isServerReachable() {
        try {
            final var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/workitems"))
                    .timeout(Duration.ofSeconds(1))
                    .GET().build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
