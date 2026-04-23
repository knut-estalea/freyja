package com.impact.freyja.loadgen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Bounded sliding window of recently-sent key indices with their send time.
 * Used to pick a "duplicate" replay: only entries within {@code maxAgeMillis}
 * are eligible, so replays are guaranteed to land inside the system's 60s
 * duplicate-detection window.
 */
public final class RecentWindow {

    private record Entry(int keyIndex, long sentAtNanos) {}

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final long maxAgeNanos;
    private final int maxSize;

    public RecentWindow(long maxAgeMillis, int maxSize) {
        this.maxAgeNanos = maxAgeMillis * 1_000_000L;
        this.maxSize = maxSize;
    }

    public synchronized void record(int keyIndex, long nowNanos) {
        entries.addLast(new Entry(keyIndex, nowNanos));
        evictExpired(nowNanos);
        while (entries.size() > maxSize) {
            entries.pollFirst();
        }
    }

    /** Returns -1 if the window is empty. */
    public synchronized int sampleReplay(Random rng, long nowNanos) {
        evictExpired(nowNanos);
        if (entries.isEmpty()) {
            return -1;
        }
        int target = rng.nextInt(entries.size());
        int i = 0;
        for (Entry e : entries) {
            if (i++ == target) {
                return e.keyIndex();
            }
        }
        return -1;
    }

    public synchronized int size() {
        return entries.size();
    }

    private void evictExpired(long nowNanos) {
        while (!entries.isEmpty() && nowNanos - entries.peekFirst().sentAtNanos() > maxAgeNanos) {
            entries.pollFirst();
        }
    }
}
