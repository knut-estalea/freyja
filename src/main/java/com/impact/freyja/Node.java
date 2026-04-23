package com.impact.freyja;

public record Node(String id, String host, int port, long token, boolean alive) {

    public Node(String id, String host, int port, long token) {
        this(id, host, port, token, true);
    }

    public Node withAlive(boolean alive) {
        return new Node(id, host, port, token, alive);
    }
}

