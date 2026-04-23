package com.impact.freyja;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class DynamoRingService {

    private final RingProperties ringProperties;
    private final ConcurrentMap<String, Node> nodesById = new ConcurrentHashMap<>();

    public DynamoRingService(RingProperties ringProperties) {
        this.ringProperties = ringProperties;
    }

    public Node addNode(String id, String host, int port) {
        long token = hash(id + "@" + host + ":" + port);
        Node node = new Node(id, host, port, token);
        nodesById.put(id, node);
        return node;
    }

    public Node removeNode(String id) {
        Node removed = nodesById.remove(id);
        if (removed == null) {
            throw new NoSuchElementException("Node not found: " + id);
        }
        return removed;
    }

    public RingSnapshot snapshot() {
        return new RingSnapshot(sortedNodes());
    }

    public KeyLookupResult locate(String key) {
        List<Node> ordered = sortedNodes();
        if (ordered.isEmpty()) {
            throw new NoSuchElementException("Ring is empty. Add nodes first.");
        }

        long keyHash = hash(key);
        int primaryIndex = findPrimaryIndex(ordered, keyHash);
        Node primary = ordered.get(primaryIndex);
        List<Node> preferenceList = buildPreferenceList(ordered, primaryIndex);
        return new KeyLookupResult(key, keyHash, primary, preferenceList);
    }

    public long hash(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private List<Node> sortedNodes() {
        return nodesById.values().stream()
                .sorted(Comparator.comparingLong(Node::token).thenComparing(Node::id))
                .toList();
    }

    private int findPrimaryIndex(List<Node> ordered, long keyHash) {
        TreeMap<Long, Integer> tokenToIndex = new TreeMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            tokenToIndex.put(ordered.get(i).token(), i);
        }

        Map.Entry<Long, Integer> ceiling = tokenToIndex.ceilingEntry(keyHash);
        if (ceiling != null) {
            return ceiling.getValue();
        }
        return tokenToIndex.firstEntry().getValue();
    }

    private List<Node> buildPreferenceList(List<Node> ordered, int primaryIndex) {
        int rf = Math.max(1, ringProperties.getReplicationFactor());
        int target = Math.min(rf, ordered.size());

        LinkedHashSet<Node> preference = new LinkedHashSet<>();
        for (int i = 0; i < ordered.size() && preference.size() < target; i++) {
            int idx = (primaryIndex + i) % ordered.size();
            preference.add(ordered.get(idx));
        }
        return new ArrayList<>(preference);
    }
}

