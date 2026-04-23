package com.impact.freyja;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Periodically polls every peer node's actuator metrics and caches an
 * aggregated cluster snapshot. The cluster status UI reads the cached snapshot
 * via {@link ClusterStatusController} so HTTP requests from the browser do not
 * trigger live fan-out.
 *
 * The local node's counters are read directly from the {@link MeterRegistry};
 * peers are queried over HTTP with a short timeout, in parallel, on virtual
 * threads.
 */
@Service
public class ClusterStatusAggregator {

    private static final Logger logger = LoggerFactory.getLogger(ClusterStatusAggregator.class);

    private static final String METRIC_NEW = "requests.new";
    private static final String METRIC_DUP = "requests.duplicate";

    private final DynamoRingService ringService;
    private final NodeIdentity nodeIdentity;
    private final MeterRegistry meterRegistry;
    private final RestClient restClient;
    private final ExecutorService fanOutExecutor;

    private final AtomicReference<ClusterStatus> latest =
            new AtomicReference<>(new ClusterStatus(List.of(), new Totals(0, 0), null));

    public ClusterStatusAggregator(
            DynamoRingService ringService,
            NodeIdentity nodeIdentity,
            MeterRegistry meterRegistry) {
        this.ringService = ringService;
        this.nodeIdentity = nodeIdentity;
        this.meterRegistry = meterRegistry;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(1).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(2).toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.fanOutExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PostConstruct
    void primeOnStartup() {
        // Avoid serving an empty snapshot for the first poll interval.
        refresh();
    }

    @PreDestroy
    void shutdown() {
        fanOutExecutor.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${freyja.cluster-status.poll-interval-ms:2000}")
    public void refresh() {
        try {
            List<Node> nodes = ringService.sortedNodes();

            List<CompletableFuture<NodeStatus>> futures = nodes.stream()
                    .map(n -> CompletableFuture.supplyAsync(() -> statusFor(n), fanOutExecutor))
                    .toList();

            List<NodeStatus> nodeStatuses = futures.stream().map(CompletableFuture::join).toList();

            long totalNew = nodeStatuses.stream()
                    .filter(n -> !n.unreachable())
                    .mapToLong(NodeStatus::newCount)
                    .sum();
            long totalDup = nodeStatuses.stream()
                    .filter(n -> !n.unreachable())
                    .mapToLong(NodeStatus::dupCount)
                    .sum();

            latest.set(new ClusterStatus(nodeStatuses, new Totals(totalNew, totalDup), Instant.now()));
        } catch (Exception ex) {
            // Don't let scheduler swallow this silently; keep the previous snapshot.
            logger.warn("Cluster status refresh failed; serving stale snapshot", ex);
        }
    }

    public ClusterStatus latest() {
        return latest.get();
    }

    private NodeStatus statusFor(Node node) {
        boolean self = nodeIdentity.isSelf(node);
        try {
            long newCount;
            long dupCount;
            if (self) {
                newCount = readLocal(METRIC_NEW);
                dupCount = readLocal(METRIC_DUP);
            } else {
                newCount = fetchRemote(node, METRIC_NEW);
                dupCount = fetchRemote(node, METRIC_DUP);
            }
            return new NodeStatus(
                    node.id(), node.host(), node.port(), node.alive(), self,
                    false, newCount, dupCount, null);
        } catch (Exception ex) {
            logger.debug("Failed to fetch metrics from node {} ({}:{}): {}",
                    node.id(), node.host(), node.port(), ex.toString());
            return new NodeStatus(
                    node.id(), node.host(), node.port(), node.alive(), self,
                    true, 0L, 0L, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private long readLocal(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0L : (long) counter.count();
    }

    @SuppressWarnings("unchecked")
    private long fetchRemote(Node node, String name) {
        String url = "http://" + node.host() + ":" + node.port() + "/actuator/metrics/" + name;
        Map<String, Object> body = restClient.get().uri(url).retrieve().body(Map.class);
        if (body == null) {
            return 0L;
        }
        Object measurements = body.get("measurements");
        if (!(measurements instanceof List<?> list)) {
            return 0L;
        }
        for (Object m : list) {
            if (m instanceof Map<?, ?> entry && "COUNT".equals(entry.get("statistic"))) {
                Object value = entry.get("value");
                if (value instanceof Number num) {
                    return num.longValue();
                }
            }
        }
        return 0L;
    }

    public record ClusterStatus(List<NodeStatus> nodes, Totals totals, Instant updatedAt) {
    }

    public record Totals(long newCount, long dupCount) {
    }

    public record NodeStatus(
            String id,
            String host,
            int port,
            boolean alive,
            boolean self,
            boolean unreachable,
            long newCount,
            long dupCount,
            String error) {
    }
}
