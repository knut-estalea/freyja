package com.impact.freyja;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Performs an HTTP POST to a peer node's {@code /internal/classify} endpoint.
 *
 * No retries are performed here; the caller (RequestController) walks the
 * preference list on failure.
 */
@Component
public class RemoteClassifier {

    private final RestClient restClient;

    public RemoteClassifier() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(2).toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public ClassifyResponse classifyOn(Node node, String key) {
        String url = "http://" + node.host() + ":" + node.port() + "/internal/classify";
        return restClient.post()
                .uri(url)
                .body(new InternalClassifyRequest(key))
                .retrieve()
                .body(ClassifyResponse.class);
    }

    public record InternalClassifyRequest(String key) {
    }
}
