package com.example.search.api;

import com.example.search.application.maintenance.ConsistencyReport;
import com.example.search.application.maintenance.RebuildJob;
import com.example.search.application.maintenance.SearchConsistencyService;
import com.example.search.application.maintenance.SearchRebuildService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Deliberately narrow operator surface; physical indices and raw DSL never cross this API. */
@RestController
@RequestMapping(value = "/api/admin/search", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
@ConditionalOnProperty(prefix = "search.maintenance", name = "enabled", havingValue = "true")
public class SearchMaintenanceController {
    private final SearchConsistencyService consistency;
    private final SearchRebuildService rebuilds;

    public SearchMaintenanceController(SearchConsistencyService consistency, SearchRebuildService rebuilds) {
        this.consistency = consistency;
        this.rebuilds = rebuilds;
    }

    @PostMapping("/rebuilds")
    public ResponseEntity<RebuildStartResponse> startRebuild() {
        return ResponseEntity.accepted().body(new RebuildStartResponse(rebuilds.startRebuild()));
    }

    @GetMapping("/rebuilds/{jobId}")
    public RebuildJob getRebuild(@PathVariable UUID jobId) {
        return rebuilds.getRebuild(jobId);
    }

    @PostMapping("/consistency-checks")
    public ConsistencyReport consistencyCheck() {
        return consistency.check();
    }

    @PostMapping("/products/{productId}/repair")
    public ResponseEntity<Void> repair(@PathVariable long productId) {
        consistency.repairProduct(productId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/outbox/{eventId}/retry")
    public ResponseEntity<Void> retry(@PathVariable UUID eventId) {
        consistency.retryFailed(eventId);
        return ResponseEntity.accepted().build();
    }

    public record RebuildStartResponse(UUID jobId) { }
}
