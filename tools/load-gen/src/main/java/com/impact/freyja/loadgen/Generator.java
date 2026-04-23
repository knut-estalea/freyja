package com.impact.freyja.loadgen;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Steady-rate traffic generator. Schedules sends at uniform nanosecond
 * intervals and dispatches each onto a virtual thread.
 */
public final class Generator {

    private final List<String> targets;
    private final double ratePerSec;
    private final Duration duration;
    private final double duplicateRatio;
    private final KeyPool pool;
    private final RecentWindow window;
    private final HttpClient client;
    private final long seed;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong attemptedDuplicates = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong non2xx = new AtomicLong();

    public Generator(List<String> targets,
                     double ratePerSec,
                     Duration duration,
                     double duplicateRatio,
                     KeyPool pool,
                     RecentWindow window,
                     HttpClient client,
                     long seed) {
        this.targets = List.copyOf(targets);
        this.ratePerSec = ratePerSec;
        this.duration = duration;
        this.duplicateRatio = duplicateRatio;
        this.pool = pool;
        this.window = window;
        this.client = client;
        this.seed = seed;
    }

    public Stats run() throws InterruptedException {
        long intervalNanos = (long) (1_000_000_000.0 / ratePerSec);
        long startNanos = System.nanoTime();
        long endNanos = startNanos + duration.toNanos();
        long nextDueNanos = startNanos;

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                long now = System.nanoTime();
                if (now >= endNanos) {
                    break;
                }
                if (now < nextDueNanos) {
                    LockSupport.parkNanos(nextDueNanos - now);
                }
                nextDueNanos += intervalNanos;
                exec.submit(this::sendOne);
            }
            // exec.close() (try-with-resources) will await all in-flight sends.
        }

        return new Stats(
                sent.get(),
                attemptedDuplicates.get(),
                errors.get(),
                non2xx.get(),
                System.nanoTime() - startNanos
        );
    }

    private void sendOne() {
        Random rng = ThreadLocalRandom.current();
        long now = System.nanoTime();

        int keyIndex;
        boolean isReplay = rng.nextDouble() < duplicateRatio;
        if (isReplay) {
            int replay = window.sampleReplay(rng, now);
            if (replay >= 0) {
                keyIndex = replay;
                attemptedDuplicates.incrementAndGet();
            } else {
                // Window not yet warmed up -> fall back to a fresh sample.
                keyIndex = pool.sampleIndex(rng);
            }
        } else {
            keyIndex = pool.sampleIndex(rng);
        }

        String target = targets.get(rng.nextInt(targets.size()));
        URI uri = URI.create(stripTrailingSlash(target)
                + "/request?session=" + pool.session(keyIndex)
                + "&program=" + pool.program(keyIndex));
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        try {
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2) {
                non2xx.incrementAndGet();
            } else {
                window.record(keyIndex, System.nanoTime());
            }
            sent.incrementAndGet();
        } catch (Exception e) {
            errors.incrementAndGet();
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public record Stats(long sent, long attemptedDuplicates, long errors, long non2xx, long elapsedNanos) {
        public double effectiveRate() {
            return sent / (elapsedNanos / 1_000_000_000.0);
        }
    }
}
