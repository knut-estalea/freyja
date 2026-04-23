package com.impact.freyja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;

/**
 * End-to-end test that boots three full Spring Boot contexts in the same JVM
 * (each with its own embedded Tomcat on a random port), wires them into a
 * shared ring, and exercises the inter-node forwarding path that none of the
 * other tests cover.
 *
 * <p>Verifies:
 * <ul>
 *   <li>A request arriving on a non-owner node is forwarded to the owner.
 *   <li>The classification reflects the owner's cache state, and the response
 *       attributes the work to the owner via the {@code node} field.
 *   <li>Counter increments on the owner (asserted via Actuator).
 *   <li>Failing the owner causes the receiving node to fall over to the next
 *       alive replica in the preference list.
 * </ul>
 */
@TestInstance(Lifecycle.PER_CLASS)
class MultiInstanceClusterTest {

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final List<NodeAddr> nodes = new ArrayList<>();
    private final RestClient http = RestClient.create();

    private record NodeAddr(String id, String host, int port) {
        String baseUrl() {
            return "http://" + host + ":" + port;
        }
    }

    @BeforeAll
    void startCluster() {
        for (int i = 1; i <= 3; i++) {
            String id = "cluster-n" + i;
            ConfigurableApplicationContext ctx = SpringApplication.run(Main.class,
                    "--server.port=0",
                    "--freyja.node.id=" + id,
                    "--freyja.node.host=127.0.0.1",
                    "--spring.application.name=freyja-test",
                    "--logging.level.com.impact.freyja=INFO");
            contexts.add(ctx);
            int port = Integer.parseInt(
                    ctx.getEnvironment().getProperty("local.server.port"));
            nodes.add(new NodeAddr(id, "127.0.0.1", port));
        }

        // Wire every node into every ring (each instance only knows itself
        // after self-registration; we add the others manually since we are
        // not running with topology sync).
        for (NodeAddr ringOwner : nodes) {
            for (NodeAddr peer : nodes) {
                if (peer.id().equals(ringOwner.id())) {
                    continue;
                }
                http.post()
                        .uri(ringOwner.baseUrl() + "/ring/nodes")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(new RingController.AddNodeRequest(peer.id(), peer.host(), peer.port()))
                        .retrieve()
                        .toBodilessEntity();
            }
        }
    }

    @AfterAll
    void stopCluster() {
        contexts.forEach(ConfigurableApplicationContext::close);
    }

    @Test
    void forwardingFromNonOwnerProducesCorrectClassificationAndAttributesToOwner() {
        String session = "alice";
        int program = 614;
        String key = program + "|" + session;

        NodeAddr owner = primaryFor(nodes.get(0), key);
        NodeAddr nonOwner = nodes.stream()
                .filter(n -> !n.id().equals(owner.id()))
                .findFirst()
                .orElseThrow();

        ClassifyResponse first = sendRequest(nonOwner, session, program);
        assertEquals(Classification.NEW, first.classification());
        assertEquals(owner.id(), first.node(),
                "non-owner forwarded — response should be attributed to the owner");

        ClassifyResponse second = sendRequest(nonOwner, session, program);
        assertEquals(Classification.DUPLICATE, second.classification());
        assertEquals(owner.id(), second.node());

        // Counter assertions: duplicate counter on owner should have incremented.
        assertTrue(counterValue(owner, "requests.duplicate") >= 1.0,
                "owner's duplicate counter should reflect the second request");
        assertTrue(counterValue(owner, "requests.new") >= 1.0,
                "owner's new counter should reflect the first request");
        // Non-owner did not classify, so its counters should remain zero for this exchange.
        assertEquals(0.0, counterValue(nonOwner, "requests.duplicate"),
                "non-owner should not have incremented duplicate counter");
    }

    @Test
    void failingOwnerCausesFailoverToReplica() {
        String session = "bob";
        int program = 42;
        String key = program + "|" + session;

        NodeAddr owner = primaryFor(nodes.get(0), key);

        // Mark owner as failed in the receiving node's view AND in its own
        // ring (so it doesn't accept the forwarded call). We mark it on every
        // node to fully simulate a down host.
        for (NodeAddr n : nodes) {
            http.post()
                    .uri(n.baseUrl() + "/ring/nodes/" + owner.id() + "/fail")
                    .retrieve()
                    .toBodilessEntity();
        }

        try {
            // Pick any node that isn't the now-dead owner as the request entry point.
            NodeAddr entry = nodes.stream()
                    .filter(n -> !n.id().equals(owner.id()))
                    .findFirst()
                    .orElseThrow();
            NodeAddr expectedReplica = primaryFor(entry, key);
            assertNotEquals(owner.id(), expectedReplica.id(),
                    "after failure, the entry node should locate a different primary");

            ClassifyResponse response = sendRequest(entry, session, program);
            assertEquals(Classification.NEW, response.classification());
            assertEquals(expectedReplica.id(), response.node(),
                    "failover should route to the next alive replica");
        } finally {
            for (NodeAddr n : nodes) {
                http.post()
                        .uri(n.baseUrl() + "/ring/nodes/" + owner.id() + "/recover")
                        .retrieve()
                        .toBodilessEntity();
            }
        }
    }

    private NodeAddr primaryFor(NodeAddr askVia, String key) {
        KeyLookupResult result = http.get()
                .uri(askVia.baseUrl() + "/ring/locate?key={k}", key)
                .retrieve()
                .body(KeyLookupResult.class);
        assertNotNull(result);
        Node primary = result.primary();
        return nodes.stream()
                .filter(n -> n.id().equals(primary.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Located node not in test cluster: " + primary));
    }

    private ClassifyResponse sendRequest(NodeAddr target, String session, int program) {
        return http.get()
                .uri(target.baseUrl() + "/request?session={s}&program={p}", session, program)
                .retrieve()
                .body(ClassifyResponse.class);
    }

    @SuppressWarnings("unchecked")
    private double counterValue(NodeAddr target, String metricName) {
        java.util.Map<String, Object> body;
        try {
            body = http.get()
                    .uri(target.baseUrl() + "/actuator/metrics/" + metricName)
                    .retrieve()
                    .body(java.util.Map.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ex) {
            // Counter is created lazily on first increment; absent == 0.
            return 0.0;
        }
        if (body == null) {
            return 0.0;
        }
        List<java.util.Map<String, Object>> measurements =
                (List<java.util.Map<String, Object>>) body.get("measurements");
        if (measurements == null || measurements.isEmpty()) {
            return 0.0;
        }
        for (java.util.Map<String, Object> m : measurements) {
            if ("COUNT".equals(m.get("statistic"))) {
                return ((Number) m.get("value")).doubleValue();
            }
        }
        return 0.0;
    }
}
