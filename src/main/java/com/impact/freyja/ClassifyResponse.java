package com.impact.freyja;

import java.time.Instant;

public record ClassifyResponse(String key, Classification classification, Instant timestamp, String node) {
}
