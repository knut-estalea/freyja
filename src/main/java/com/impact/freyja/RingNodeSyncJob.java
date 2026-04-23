package com.impact.freyja;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RingNodeSyncJob {

    private static final Logger logger = LoggerFactory.getLogger(RingNodeSyncJob.class);

    private final RingProperties ringProperties;
    private final DynamoRingService ringService;
    private final RestClient restClient;

    public RingNodeSyncJob(RingProperties ringProperties, DynamoRingService ringService) {
        this.ringProperties = ringProperties;
        this.ringService = ringService;
        this.restClient = RestClient.create();
    }

    @Scheduled(fixedDelayString = "${freyja.ring.sync-interval-ms:30000}")
    public void syncNodes() {
        if (!ringProperties.isSyncEnabled()) {
            return;
        }

        String url = ringProperties.getNodesUrl();
        if (url == null || url.isBlank()) {
            logger.warn("Node sync enabled, but freyja.ring.nodes-url is empty. Skipping sync.");
            return;
        }

        try {
            RingController.AddNodeRequest[] response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(RingController.AddNodeRequest[].class);
            List<RingController.AddNodeRequest> nodes = response == null ? List.of() : List.of(response);
            ringService.reconcileNodes(nodes);
        } catch (Exception ex) {
            logger.warn("Failed to sync ring nodes from {}: {}", url, ex.getMessage());
        }
    }
}

