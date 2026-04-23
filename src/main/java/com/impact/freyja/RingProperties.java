package com.impact.freyja;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "freyja.ring")
public class RingProperties {

    private int replicationFactor = 3;
    private boolean syncEnabled = false;
    private String nodesUrl;
    private long syncIntervalMs = 30000;

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public void setReplicationFactor(int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public String getNodesUrl() {
        return nodesUrl;
    }

    public void setNodesUrl(String nodesUrl) {
        this.nodesUrl = nodesUrl;
    }

    public long getSyncIntervalMs() {
        return syncIntervalMs;
    }

    public void setSyncIntervalMs(long syncIntervalMs) {
        this.syncIntervalMs = syncIntervalMs;
    }
}
