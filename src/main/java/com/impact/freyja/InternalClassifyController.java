package com.impact.freyja;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inter-node lookup endpoint. Always processes locally without re-forwarding,
 * guaranteeing at most one network hop per client request.
 */
@RestController
@RequestMapping("/internal")
public class InternalClassifyController {

    private final DuplicateDetectionService detectionService;

    public InternalClassifyController(DuplicateDetectionService detectionService) {
        this.detectionService = detectionService;
    }

    @PostMapping("/classify")
    public ClassifyResponse classify(@RequestBody RemoteClassifier.InternalClassifyRequest body) {
        return detectionService.classify(body.key());
    }
}
