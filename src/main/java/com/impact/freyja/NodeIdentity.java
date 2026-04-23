package com.impact.freyja;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves this instance's identity (id/host/port) from configuration with
 * sensible defaults, and self-registers it into the local ring at startup so
 * the node recognizes itself as the owner of keys hashing to its own token.
 */
@Component
public class NodeIdentity {

    private static final Logger logger = LoggerFactory.getLogger(NodeIdentity.class);

    private final NodeIdentityProperties properties;
    private final DynamoRingService ringService;
    private final int serverPort;
    private final String applicationName;

    private String id;
    private String host;
    private int port;

    public NodeIdentity(
            NodeIdentityProperties properties,
            DynamoRingService ringService,
            @Value("${server.port:8080}") int serverPort,
            @Value("${spring.application.name:freyja}") String applicationName) {
        this.properties = properties;
        this.ringService = ringService;
        this.serverPort = serverPort;
        this.applicationName = applicationName;
    }

    @PostConstruct
    void init() {
        this.port = properties.getPort() != null ? properties.getPort() : serverPort;
        this.host = properties.getHost() != null ? properties.getHost() : detectHost();
        this.id = properties.getId() != null ? properties.getId() : applicationName + "-" + port;
        if (properties.isSelfRegister()) {
            ringService.addNode(id, host, port);
            logger.info("Self-registered node id={} host={} port={}", id, host, port);
        } else {
            logger.info("Self-registration disabled; identity: id={} host={} port={}", id, host, port);
        }
    }

    public String id() {
        return id;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean isSelf(Node node) {
        return node != null && id.equals(node.id());
    }

    private static String detectHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException ex) {
            return "127.0.0.1";
        }
    }
}
