package com.rorm.client.research.rewind;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read endpoint for the post-run rewind slideshow.
 *
 * <p>{@code GET} returns the cached {@link RewindDTO} (one of
 * {@code PENDING / IN_PROGRESS / READY / FAILED}) or 404 if the run
 * isn't eligible (descriptive runs, mock runs, unknown ids).
 *
 * <p>{@code POST /regenerate} drops the cache for that run and starts
 * a fresh composition — useful when the narrator is changed and the
 * cached version is stale.
 */
@RestController
@RequestMapping("/research/{runId}/rewind")
@RequiredArgsConstructor
public class RewindController {

    private final RewindService rewindService;

    @GetMapping
    public ResponseEntity<RewindDTO> get(@PathVariable String runId) {
        return rewindService.get(runId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/regenerate")
    public ResponseEntity<Void> regenerate(@PathVariable String runId) {
        rewindService.invalidate(runId);
        rewindService.start(runId);
        return ResponseEntity.accepted().build();
    }
}
