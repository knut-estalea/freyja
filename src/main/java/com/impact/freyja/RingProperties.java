package com.impact.freyja;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "freyja.ring")
public class RingProperties {

    private int replicationFactor = 3;

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public void setReplicationFactor(int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }
}

