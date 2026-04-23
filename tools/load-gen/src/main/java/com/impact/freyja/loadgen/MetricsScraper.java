package com.impact.freyja.loadgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Reads a single Micrometer counter value from an instance's
 * {@code /actuator/metrics/{name}} endpoint.
 */
public final class MetricsScraper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;

    public MetricsScraper(HttpClient client) {
        this.client = client;
    }

    /**
     * Returns the COUNT measurement for the named metric, or 0.0 if the
     * metric is not yet registered (e.g., counter never incremented).
     * Throws on transport or parse errors.
     */
    public double readCounter(String baseUrl, String metricName) throws Exception {
        URI uri = URI.create(stripTrailingSlash(baseUrl) + "/actuator/metrics/" + metricName);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            return 0.0;
        }
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Metrics fetch failed: " + uri + " -> HTTP " + resp.statusCode());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode measurements = root.path("measurements");
        for (JsonNode m : measurements) {
            if ("COUNT".equals(m.path("statistic").asText())) {
                return m.path("value").asDouble(0.0);
            }
        }
        return 0.0;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
