package com.impact.freyja;

import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ring")
public class RingController {

    private final DynamoRingService ringService;

    public RingController(DynamoRingService ringService) {
        this.ringService = ringService;
    }

    @PostMapping("/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public Node addNode(@RequestBody AddNodeRequest request) {
        return ringService.addNode(request.id(), request.host(), request.port());
    }

    @DeleteMapping("/nodes/{id}")
    public Node removeNode(@PathVariable String id) {
        return ringService.removeNode(id);
    }

    @PostMapping("/nodes/{id}/fail")
    public Node failNode(@PathVariable String id) {
        return ringService.setAlive(id, false);
    }

    @PostMapping("/nodes/{id}/recover")
    public Node recoverNode(@PathVariable String id) {
        return ringService.setAlive(id, true);
    }

    @GetMapping
    public RingSnapshot getRing() {
        return ringService.snapshot();
    }

    @GetMapping("/locate")
    public KeyLookupResult locate(@RequestParam String key) {
        return ringService.locate(key);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNoSuchElement(NoSuchElementException ex) {
        return Map.of("error", ex.getMessage());
    }

    public record AddNodeRequest(String id, String host, int port) {
    }
}

