package com.impact.freyja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamoRingFailureTest {

    private DynamoRingService service;

    @BeforeEach
    void setUp() {
        RingProperties props = new RingProperties();
        props.setReplicationFactor(3);
        service = new DynamoRingService(props);
        service.addNode("n1", "127.0.0.1", 9001);
        service.addNode("n2", "127.0.0.1", 9002);
        service.addNode("n3", "127.0.0.1", 9003);
    }

    @Test
    void newNodeStartsAlive() {
        assertTrue(service.snapshot().nodes().stream().allMatch(Node::alive));
    }

    @Test
    void setAliveTogglesFlag() {
        Node failed = service.setAlive("n1", false);
        assertFalse(failed.alive());
        Node recovered = service.setAlive("n1", true);
        assertTrue(recovered.alive());
    }

    @Test
    void setAliveOnUnknownNodeThrows() {
        assertThrows(NoSuchElementException.class, () -> service.setAlive("nope", false));
    }

    @Test
    void locateSkipsDeadNodesInPreferenceList() {
        KeyLookupResult before = service.locate("k");
        Node primary = before.primary();
        service.setAlive(primary.id(), false);

        KeyLookupResult after = service.locate("k");
        assertFalse(after.preferenceList().stream().anyMatch(n -> n.id().equals(primary.id())));
        assertEquals(2, after.preferenceList().size());
    }

    @Test
    void locateThrowsWhenAllNodesDead() {
        service.setAlive("n1", false);
        service.setAlive("n2", false);
        service.setAlive("n3", false);
        assertThrows(NoSuchElementException.class, () -> service.locate("k"));
    }

    @Test
    void reconcileNodesResetsAliveToTrue() {
        service.setAlive("n1", false);
        service.reconcileNodes(java.util.List.of(
                new RingController.AddNodeRequest("n1", "127.0.0.1", 9001),
                new RingController.AddNodeRequest("n2", "127.0.0.1", 9002),
                new RingController.AddNodeRequest("n3", "127.0.0.1", 9003)
        ));
        assertTrue(service.snapshot().nodes().stream().allMatch(Node::alive));
    }
}
