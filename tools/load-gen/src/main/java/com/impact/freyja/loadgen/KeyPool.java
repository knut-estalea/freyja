package com.impact.freyja.loadgen;

import java.util.Random;

/**
 * Pre-generated pool of (program, session) pairs with a Zipfian sampler.
 * The Zipf distribution makes a few keys "hot" so the generated traffic
 * resembles real-world skew rather than uniform noise.
 */
public final class KeyPool {

    private final String[] keys;
    private final int[] programs;
    private final String[] sessions;
    private final double[] cumulativeWeights;

    public KeyPool(int size, double zipfExponent, long seed) {
        if (size <= 0) {
            throw new IllegalArgumentException("pool size must be > 0");
        }
        Random rng = new Random(seed);
        this.keys = new String[size];
        this.programs = new int[size];
        this.sessions = new String[size];

        for (int i = 0; i < size; i++) {
            int program = 100 + rng.nextInt(900);
            String session = randomAlphanumeric(rng, 8 + rng.nextInt(5));
            this.programs[i] = program;
            this.sessions[i] = session;
            this.keys[i] = program + "|" + session;
        }

        // Zipf weights: w_i = 1 / (i+1)^s, then normalize and accumulate.
        this.cumulativeWeights = new double[size];
        double total = 0.0;
        for (int i = 0; i < size; i++) {
            total += 1.0 / Math.pow(i + 1, zipfExponent);
            cumulativeWeights[i] = total;
        }
        for (int i = 0; i < size; i++) {
            cumulativeWeights[i] /= total;
        }
    }

    public int size() {
        return keys.length;
    }

    public int sampleIndex(Random rng) {
        double r = rng.nextDouble();
        // Binary search for the first cumulative weight >= r.
        int lo = 0, hi = cumulativeWeights.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulativeWeights[mid] < r) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    public int program(int idx) {
        return programs[idx];
    }

    public String session(int idx) {
        return sessions[idx];
    }

    public String key(int idx) {
        return keys[idx];
    }

    private static String randomAlphanumeric(Random rng, int len) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) {
            buf[i] = alphabet.charAt(rng.nextInt(alphabet.length()));
        }
        return new String(buf);
    }
}
