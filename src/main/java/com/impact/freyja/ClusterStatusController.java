package com.impact.freyja;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cluster")
public class ClusterStatusController {

    private final ClusterStatusAggregator aggregator;

    public ClusterStatusController(ClusterStatusAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @GetMapping("/status")
    public ClusterStatusAggregator.ClusterStatus status() {
        return aggregator.latest();
    }
}
