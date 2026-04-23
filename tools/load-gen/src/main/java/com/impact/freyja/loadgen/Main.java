package com.impact.freyja.loadgen;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "load-gen",
        mixinStandardHelpOptions = true,
        description = "Generates simulated traffic for the Freyja duplicate-detection cluster."
)
public final class Main implements Callable<Integer> {

    @Option(names = "--targets", required = true, split = ",",
            description = "Comma-separated list of instance base URLs, e.g. http://localhost:9001,http://localhost:9002")
    List<String> targets;

    @Option(names = "--rate", defaultValue = "50",
            description = "Target request rate, requests per second (default: ${DEFAULT-VALUE}).")
    double rate;

    @Option(names = "--duration", defaultValue = "1m",
            description = "Run duration, ISO-8601 or shorthand like 30s/5m/1h (default: ${DEFAULT-VALUE}).")
    String durationSpec;

    @Option(names = "--duplicate-ratio", defaultValue = "0.25",
            description = "Fraction of requests that should be replays of recent keys (default: ${DEFAULT-VALUE}).")
    double duplicateRatio;

    @Option(names = "--pool-size", defaultValue = "100",
            description = "Number of distinct (program, session) keys to pre-generate (default: ${DEFAULT-VALUE}).")
    int poolSize;

    @Option(names = "--zipf-exponent", defaultValue = "1.2",
            description = "Zipf exponent for non-replay key selection; higher = more skewed (default: ${DEFAULT-VALUE}).")
    double zipfExponent;

    @Option(names = "--window-seconds", defaultValue = "50",
            description = "Max age (seconds) of keys eligible for replay; should be < 60 to stay inside the duplicate window (default: ${DEFAULT-VALUE}).")
    int windowSeconds;

    @Option(names = "--window-max-size", defaultValue = "10000",
            description = "Hard cap on the recent-keys window (default: ${DEFAULT-VALUE}).")
    int windowMaxSize;

    @Option(names = "--seed", defaultValue = "42",
            description = "Random seed for reproducible key generation (default: ${DEFAULT-VALUE}).")
    long seed;

    @Option(names = "--validate", defaultValue = "true", negatable = true,
            description = "If true, scrape Actuator counters before/after and print observed-vs-expected duplicate ratio.")
    boolean validate;

    @Option(names = "--validate-tolerance", defaultValue = "0.10",
            description = "Acceptable absolute difference between observed and expected duplicate ratio (default: ${DEFAULT-VALUE}).")
    double validateTolerance;

    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }

    @Override
    public Integer call() throws Exception {
        Duration duration = parseDuration(durationSpec);

        System.out.printf("Targets: %s%n", targets);
        System.out.printf("Rate: %.1f req/s   Duration: %s   Duplicate ratio: %.3f%n",
                rate, duration, duplicateRatio);
        System.out.printf("Key pool: size=%d  zipf=%.2f  seed=%d%n",
                poolSize, zipfExponent, seed);
        System.out.printf("Replay window: %ds (max %d entries)%n", windowSeconds, windowMaxSize);
        System.out.println();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        Map<String, Counters> before = Map.of();
        if (validate) {
            before = snapshotCounters(client);
            System.out.println("Pre-run counters: " + formatCounters(before));
            System.out.println();
        }

        KeyPool pool = new KeyPool(poolSize, zipfExponent, seed);
        RecentWindow window = new RecentWindow(windowSeconds * 1000L, windowMaxSize);
        Generator gen = new Generator(targets, rate, duration, duplicateRatio, pool, window, client, seed);

        System.out.println("Starting traffic...");
        Generator.Stats stats = gen.run();
        System.out.printf("Done. sent=%d  attempted-duplicates=%d  errors=%d  non-2xx=%d  effective-rate=%.2f req/s%n",
                stats.sent(), stats.attemptedDuplicates(), stats.errors(), stats.non2xx(), stats.effectiveRate());

        if (!validate) {
            return 0;
        }

        // Give nodes a moment to flush counter updates.
        Thread.sleep(500);

        Map<String, Counters> after = snapshotCounters(client);
        System.out.println();
        System.out.println("Post-run counters: " + formatCounters(after));

        long deltaDup = 0;
        long deltaNew = 0;
        for (String t : targets) {
            Counters b = before.getOrDefault(t, Counters.ZERO);
            Counters a = after.getOrDefault(t, Counters.ZERO);
            deltaDup += Math.round(a.duplicate - b.duplicate);
            deltaNew += Math.round(a.fresh - b.fresh);
        }
        long totalClassified = deltaDup + deltaNew;
        double observedRatio = totalClassified == 0 ? 0.0 : (double) deltaDup / totalClassified;
        long expectedDup = Math.round(stats.sent() * duplicateRatio);

        System.out.println();
        System.out.println("=== Validation ===");
        System.out.printf("Classified by cluster: %d  (duplicate=%d, new=%d)%n", totalClassified, deltaDup, deltaNew);
        System.out.printf("Sent by generator:     %d  (expected duplicates ~%d)%n", stats.sent(), expectedDup);
        System.out.printf("Observed duplicate ratio: %.4f   (target %.4f, tolerance \u00b1%.2f)%n",
                observedRatio, duplicateRatio, validateTolerance);

        // Note: observed ratio will typically be slightly above target because Zipfian
        // "fresh" picks sometimes naturally hit a hot key already in the 60s window.
        if (Math.abs(observedRatio - duplicateRatio) > validateTolerance) {
            System.out.println("WARN: observed ratio outside tolerance band.");
            return 2;
        }
        System.out.println("OK: observed ratio within tolerance.");
        return 0;
    }

    private Map<String, Counters> snapshotCounters(HttpClient client) {
        MetricsScraper scraper = new MetricsScraper(client);
        Map<String, Counters> out = new HashMap<>();
        for (String t : targets) {
            try {
                double dup = scraper.readCounter(t, "requests.duplicate");
                double fresh = scraper.readCounter(t, "requests.new");
                out.put(t, new Counters(dup, fresh));
            } catch (Exception e) {
                System.err.printf("WARN: failed to read counters from %s: %s%n", t, e.getMessage());
                out.put(t, Counters.ZERO);
            }
        }
        return out;
    }

    private static String formatCounters(Map<String, Counters> snap) {
        StringBuilder sb = new StringBuilder();
        snap.forEach((t, c) -> sb.append(String.format("%n  %s -> dup=%.0f new=%.0f", t, c.duplicate, c.fresh)));
        return sb.toString();
    }

    private record Counters(double duplicate, double fresh) {
        static final Counters ZERO = new Counters(0.0, 0.0);
    }

    private static Duration parseDuration(String spec) {
        String s = spec.trim().toLowerCase();
        try {
            if (s.endsWith("ms")) return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)));
            if (s.endsWith("s"))  return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("m"))  return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("h"))  return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
            return Duration.parse(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse duration: " + spec, e);
        }
    }
}
