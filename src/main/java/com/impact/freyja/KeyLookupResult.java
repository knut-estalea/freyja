package com.impact.freyja;

import java.util.List;

public record KeyLookupResult(String key, long hash, Node primary, List<Node> preferenceList) {
}

