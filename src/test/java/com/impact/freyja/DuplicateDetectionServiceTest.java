package com.impact.freyja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DuplicateDetectionServiceTest {

    private MutableClock clock;
    private DuplicateDetectionService service;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        registry = new SimpleMeterRegistry();
        NodeIdentity identity = Mockito.mock(NodeIdentity.class);
        Mockito.when(identity.id()).thenReturn("test-node");
        service = new DuplicateDetectionService(identity, registry, clock);
    }

    @Test
    void firstRequestIsNew() {
        ClassifyResponse response = service.classify("k1");
        assertEquals(Classification.NEW, response.classification());
        assertEquals(1.0, registry.counter("requests.new").count());
        assertEquals(0.0, registry.counter("requests.duplicate").count());
    }

    @Test
    void secondRequestWithinWindowIsDuplicateAndPreservesOriginalTimestamp() {
        Instant first = clock.instant();
        service.classify("k1");
        clock.advanceSeconds(30);
        ClassifyResponse second = service.classify("k1");

        assertEquals(Classification.DUPLICATE, second.classification());
        assertEquals(first, second.timestamp(),
                "duplicate response should report the originally cached timestamp");
        assertEquals(1.0, registry.counter("requests.duplicate").count());
        assertEquals(1.0, registry.counter("requests.new").count());
    }

    @Test
    void secondRequestOutsideWindowIsNewAndUpdatesTimestamp() {
        Instant first = clock.instant();
        service.classify("k1");
        clock.advanceSeconds(61);
        ClassifyResponse second = service.classify("k1");

        assertEquals(Classification.NEW, second.classification());
        assertNotEquals(first, second.timestamp());
        assertEquals(2.0, registry.counter("requests.new").count());
        assertEquals(0.0, registry.counter("requests.duplicate").count());
    }

    @Test
    void secondRequestExactlyAtWindowBoundaryIsDuplicate() {
        service.classify("k1");
        clock.advanceSeconds(60);
        ClassifyResponse second = service.classify("k1");

        assertEquals(Classification.DUPLICATE, second.classification());
    }

    @Test
    void evictExpiredRemovesEntriesOlderThanTtl() {
        service.classify("k1");
        clock.advanceSeconds(301);
        service.evictExpired();
        assertEquals(0, service.cacheSize());
    }

    @Test
    void evictExpiredKeepsRecentEntries() {
        service.classify("k1");
        clock.advanceSeconds(299);
        service.evictExpired();
        assertEquals(1, service.cacheSize());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
