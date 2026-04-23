package com.impact.freyja;

import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public client entry point. Receives the duplicate-detection request, derives
 * the key, locates its owning node via the ring, and either processes locally
 * or forwards to the owner via {@link RemoteClassifier}. On forwarding failure
 * walks the preference list to the next alive replica.
 */
@RestController
public class RequestController {

    private static final Logger logger = LoggerFactory.getLogger(RequestController.class);

    private final DynamoRingService ringService;
    private final DuplicateDetectionService detectionService;
    private final RemoteClassifier remoteClassifier;
    private final NodeIdentity nodeIdentity;

    public RequestController(
            DynamoRingService ringService,
            DuplicateDetectionService detectionService,
            RemoteClassifier remoteClassifier,
            NodeIdentity nodeIdentity) {
        this.ringService = ringService;
        this.detectionService = detectionService;
        this.remoteClassifier = remoteClassifier;
        this.nodeIdentity = nodeIdentity;
    }

    @GetMapping("/request")
    public ClassifyResponse handleRequest(
            @RequestParam("session") String session,
            @RequestParam("program") int program) {
        String key = program + "|" + session;

        KeyLookupResult located;
        try {
            located = ringService.locate(key);
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }

        for (Node candidate : located.preferenceList()) {
            try {
                if (nodeIdentity.isSelf(candidate)) {
                    return detectionService.classify(key);
                }
                return remoteClassifier.classifyOn(candidate, key);
            } catch (Exception ex) {
                logger.warn("Classify on node {} ({}:{}) failed: {} — trying next replica",
                        candidate.id(), candidate.host(), candidate.port(), ex.getMessage());
            }
        }

        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "No reachable node could classify key: " + key);
    }
}
