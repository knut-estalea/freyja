package com.impact.freyja;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Owns the per-node duplicate-detection cache and the classification logic.
 *
 * Cache entries map a request key to the {@link Instant} of the last recorded
 * (non-duplicate) request. Entries are evicted by a scheduled sweeper after
 * the configured TTL.
 */
@Service
public class DuplicateDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(DuplicateDetectionService.class);

    static final Duration DUPLICATE_WINDOW = Duration.ofSeconds(60);
    static final Duration ENTRY_TTL = Duration.ofSeconds(300);

    private final ConcurrentMap<String, Instant> cache = new ConcurrentHashMap<>();
    private final NodeIdentity nodeIdentity;
    private final Counter duplicateCounter;
    private final Counter newCounter;
    private final Clock clock;

    @Autowired
    public DuplicateDetectionService(NodeIdentity nodeIdentity, MeterRegistry meterRegistry) {
        this(nodeIdentity, meterRegistry, Clock.systemUTC());
    }

    DuplicateDetectionService(NodeIdentity nodeIdentity, MeterRegistry meterRegistry, Clock clock) {
        this.nodeIdentity = nodeIdentity;
        this.clock = clock;
        this.duplicateCounter = Counter.builder("requests.duplicate")
                .description("Requests classified as duplicates")
                .register(meterRegistry);
        this.newCounter = Counter.builder("requests.new")
                .description("Requests classified as new")
                .register(meterRegistry);
    }

    /**
     * Atomically inspects-and-updates the cache entry for {@code key} and
     * returns the resulting classification. Counter updates and the
     * non-duplicate access log happen here so that all post-classification
     * side effects are co-located on the owning node.
     */
    public ClassifyResponse classify(String key) {
        Instant now = clock.instant();
        ClassificationOutcome outcome = new ClassificationOutcome();

        cache.compute(key, (k, existing) -> {
            if (existing == null) {
                outcome.classification = Classification.NEW;
                outcome.timestamp = now;
                return now;
            }
            if (Duration.between(existing, now).compareTo(DUPLICATE_WINDOW) <= 0) {
                outcome.classification = Classification.DUPLICATE;
                outcome.timestamp = existing;
                return existing;
            }
            outcome.classification = Classification.NEW;
            outcome.timestamp = now;
            return now;
        });

        if (outcome.classification == Classification.DUPLICATE) {
            duplicateCounter.increment();
        } else {
            newCounter.increment();
            logger.info("Request not-duplicate: key={} timestamp={} node={}",
                    key, outcome.timestamp, nodeIdentity.id());
        }
        return new ClassifyResponse(key, outcome.classification, outcome.timestamp, nodeIdentity.id());
    }

    @Scheduled(fixedDelayString = "${freyja.cache.sweep-interval-ms:30000}")
    public void evictExpired() {
        Instant cutoff = clock.instant().minus(ENTRY_TTL);
        int removed = 0;
        Iterator<Map.Entry<String, Instant>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Instant> entry = it.next();
            if (entry.getValue().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            logger.debug("Evicted {} stale cache entries", removed);
        }
    }

    int cacheSize() {
        return cache.size();
    }

    private static final class ClassificationOutcome {
        Classification classification;
        Instant timestamp;
    }
}
