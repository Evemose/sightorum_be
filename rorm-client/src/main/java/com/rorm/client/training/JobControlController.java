package com.rorm.client.training;

import com.rorm.ml.JobControl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for controlling in-flight async ML jobs.
 * Active only when durable execution is enabled (JobControl bean exists).
 */
@Slf4j
@RestController
@RequestMapping("/job/{jobId}")
@RequiredArgsConstructor
@ConditionalOnBean(JobControl.class)
public class JobControlController {

    private final JobControl jobControl;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable UUID jobId) {
        var status = jobControl.status(jobId);
        return ResponseEntity.ok(Map.of(
            "jobId", jobId,
            "status", status.name()
        ));
    }

    @PostMapping("/skip")
    public ResponseEntity<Map<String, Object>> skip(@PathVariable UUID jobId) {
        log.info("User requested skip for job {}", jobId);
        jobControl.skip(jobId);
        return ResponseEntity.ok(Map.of(
            "jobId", jobId,
            "action", "skipped"
        ));
    }

    @PostMapping("/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable UUID jobId) {
        log.info("User requested retry for job {}", jobId);
        var newJobId = jobControl.retry(jobId);
        return ResponseEntity.ok(Map.of(
            "originalJobId", jobId,
            "newJobId", newJobId,
            "action", "retried"
        ));
    }

    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID jobId) {
        log.info("User requested cancel for job {}", jobId);
        jobControl.cancel(jobId);
        return ResponseEntity.ok(Map.of(
            "jobId", jobId,
            "action", "cancelled"
        ));
    }
}
