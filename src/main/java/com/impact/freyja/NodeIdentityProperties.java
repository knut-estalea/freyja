package com.impact.freyja;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "freyja.node")
public class NodeIdentityProperties {

    private String id;
    private String host;
    private Integer port;
    private boolean selfRegister = true;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public boolean isSelfRegister() {
        return selfRegister;
    }

    public void setSelfRegister(boolean selfRegister) {
        this.selfRegister = selfRegister;
    }
}
