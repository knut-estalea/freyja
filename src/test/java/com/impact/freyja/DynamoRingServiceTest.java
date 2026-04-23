package com.impact.freyja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamoRingServiceTest {

    private DynamoRingService service;

    @BeforeEach
    void setUp() {
        RingProperties props = new RingProperties();
        props.setReplicationFactor(3);
        service = new DynamoRingService(props);
    }

    @Test
    void locateReturnsPrimaryAndPreferenceList() {
        service.addNode("n1", "127.0.0.1", 9001);
        service.addNode("n2", "127.0.0.1", 9002);
        service.addNode("n3", "127.0.0.1", 9003);

        KeyLookupResult result = service.locate("customer:42");

        assertNotNull(result.primary());
        assertEquals(3, result.preferenceList().size());
        assertEquals(result.primary(), result.preferenceList().getFirst());
    }

    @Test
    void locateThrowsWhenRingIsEmpty() {
        assertThrows(NoSuchElementException.class, () -> service.locate("k"));
    }

    @Test
    void removeNodeUpdatesRingSnapshot() {
        service.addNode("n1", "127.0.0.1", 9001);
        service.addNode("n2", "127.0.0.1", 9002);

        service.removeNode("n1");

        RingSnapshot snapshot = service.snapshot();
        assertEquals(1, snapshot.nodes().size());
        assertEquals("n2", snapshot.nodes().getFirst().id());
    }
}

