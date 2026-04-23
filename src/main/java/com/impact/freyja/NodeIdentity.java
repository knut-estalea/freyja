package com.impact.freyja;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Resolves this instance's identity (id/host/port) and self-registers it into
 * the local ring. Initialization is deferred until {@link
 * WebServerInitializedEvent} fires so that the actual bound port is known —
 * critical when the application is started with {@code server.port=0}.
 */
@Component
public class NodeIdentity {

    private static final Logger logger = LoggerFactory.getLogger(NodeIdentity.class);

    private final NodeIdentityProperties properties;
    private final DynamoRingService ringService;
    private final String applicationName;

    private volatile String id;
    private volatile String host;
    private volatile int port;
    private volatile boolean initialized;

    public NodeIdentity(
            NodeIdentityProperties properties,
            DynamoRingService ringService,
            @Value("${spring.application.name:freyja}") String applicationName) {
        this.properties = properties;
        this.ringService = ringService;
        this.applicationName = applicationName;
    }

    @EventListener
    public synchronized void onWebServerReady(WebServerInitializedEvent event) {
        if (initialized) {
            return;
        }
        int boundPort = event.getWebServer().getPort();
        this.port = properties.getPort() != null ? properties.getPort() : boundPort;
        this.host = properties.getHost() != null ? properties.getHost() : detectHost();
        this.id = properties.getId() != null ? properties.getId() : applicationName + "-" + this.port;
        if (properties.isSelfRegister()) {
            ringService.addNode(id, host, port);
            logger.info("Self-registered node id={} host={} port={}", id, host, port);
        } else {
            logger.info("Self-registration disabled; identity: id={} host={} port={}", id, host, port);
        }
        initialized = true;
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
        return node != null && id != null && id.equals(node.id());
    }

    private static String detectHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException ex) {
            return "127.0.0.1";
        }
    }
}

