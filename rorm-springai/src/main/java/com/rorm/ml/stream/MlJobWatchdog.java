package com.rorm.ml.stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecs.model.DesiredStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class MlJobWatchdog {

    private static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private static final Set<Integer> OOM_EXIT_CODES = Set.of(137, 139);

    private static final List<String> KNOWN_REGIONS = List.of(
        "eu-central-1", "eu-west-1", "ap-east-1", "ap-southeast-3", "ap-southeast-5"
    );

    private static final List<StreamGroup> REQUEST_STREAM_GROUPS = List.of(
        new StreamGroup("ml_training:training_requests", "training_workers"),
        new StreamGroup("ml_training:tuning_requests", "tuning_workers"),
        new StreamGroup("ml_training:stability_selection_requests", "analysis_workers"),
        new StreamGroup("ml_training:shap_requests", "shap_workers"),
        new StreamGroup("ml_training:causal_verification_requests", "causal_verification_workers")
    );

    private final WorkerTaskRegistry workerTaskRegistry;
    private final JobCompletionHandler completionHandler;
    private final EcsClientFactory ecsClients;
    private final StringRedisTemplate redisTemplate;

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT2M")
    public void scan() {
        var staleCutoff = Instant.now().minus(STALE_AFTER);
        var entries = workerTaskRegistry.snapshot();
        for (var entry : entries.entrySet()) {
            if (entry.getValue().registeredAt().isAfter(staleCutoff)) {
                continue;
            }
            try {
                checkAndResolveIfDead(entry.getKey(), entry.getValue().workerTaskArn());
            } catch (Exception e) {
                log.warn("watchdog check failed for jobId {} (taskArn {}): {}",
                    entry.getKey(), entry.getValue().workerTaskArn(), e.getMessage());
            }
        }
    }

    private void checkAndResolveIfDead(UUID jobId, String taskArn) {
        var ref = parseTaskArn(taskArn);
        if (ref == null) {
            log.warn("watchdog: unparseable task ARN '{}' for jobId {}", taskArn, jobId);
            return;
        }
        try (var ecsClient = ecsClients.forRegion(ref.region())) {
            var resp = ecsClient
                .describeTasks(b -> b.cluster(ref.cluster()).tasks(ref.taskId()));
            if (resp.tasks().isEmpty()) {
                log.debug("watchdog: DescribeTasks returned no task for {} (GC'd by ECS retention)", taskArn);
                return;
            }
            var task = resp.tasks().getFirst();
            if (!"STOPPED".equalsIgnoreCase(task.lastStatus())) {
                return;
            }
            if (task.containers().isEmpty()) {
                return;
            }
            var container = task.containers().getFirst();
            if (!isOomKill(container.exitCode(), container.reason())) {
                return;
            }

            log.warn("watchdog: synthesizing JOB_FAILED for jobId={} due to worker OOM (task={}, exit={}, reason={})",
                jobId, taskArn, container.exitCode(), container.reason());

            var synthetic = new JobEvent(
                jobId,
                JobEventType.JOB_FAILED,
                Instant.now(),
                0.0,
                "Worker task killed by OOM",
                Map.of(),
                container.reason() != null ? container.reason() : "OutOfMemory: worker killed",
                "WORKER_OOM",
                Map.of(
                    "watchdog", true,
                    "exitCode", container.exitCode() != null ? container.exitCode() : -1,
                    "taskArn", taskArn
                ),
                taskArn
            );
            completionHandler.onJobFailure(synthetic);
            workerTaskRegistry.clear(jobId);
        }
    }

    @Nullable
    private TaskRef parseTaskArn(String arn) {
        // arn:aws:ecs:<region>:<account>:task/<cluster>/<taskId>
        var parts = arn.split(":");
        if (parts.length < 6 || !"ecs".equals(parts[2])) {
            return null;
        }
        var slash = parts[5].split("/");
        if (slash.length != 3 || !"task".equals(slash[0])) {
            return null;
        }
        return new TaskRef(parts[3], slash[1], slash[2]);
    }

    private boolean isOomKill(@Nullable Integer exitCode, @Nullable String reason) {
        if (exitCode != null && OOM_EXIT_CODES.contains(exitCode)) {
            return true;
        }
        return reason != null && reason.toLowerCase().contains("outofmemor");
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT2M")
    public void cleanupOrphanPending() {
        Map<String, Set<String>> liveTaskIdsByRegion;
        try {
            liveTaskIdsByRegion = collectLiveTaskIds();
        } catch (Exception e) {
            log.warn("watchdog: failed to enumerate live ECS tasks: {}", e.getMessage());
            return;
        }
        for (var sg : REQUEST_STREAM_GROUPS) {
            try {
                ackOrphansForGroup(sg, liveTaskIdsByRegion);
            } catch (Exception e) {
                log.warn("orphan PEL cleanup failed for {}/{}: {}",
                    sg.stream(), sg.group(), e.getMessage());
            }
        }
    }

    private Map<String, Set<String>> collectLiveTaskIds() {
        Map<String, Set<String>> result = new HashMap<>();
        for (var region : KNOWN_REGIONS) {
            result.put(region, listRunningTaskIds(region));
        }
        return result;
    }

    private void ackOrphansForGroup(StreamGroup sg, Map<String, Set<String>> liveTaskIdsByRegion) {
        var summary = redisTemplate.opsForStream().pending(sg.stream(), sg.group());
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return;
        }
        summary.getPendingMessagesPerConsumer().forEach((consumer, count) -> {
            if (count <= 0 || isLiveConsumer(consumer, sg.group(), liveTaskIdsByRegion)) {
                return;
            }
            ackAllPendingFor(sg.stream(), sg.group(), consumer);
        });
    }

    private Set<String> listRunningTaskIds(String region) {
        try (var client = ecsClients.forRegion(region)) {
            var clusters = client.listClusters().clusterArns();
            Set<String> ids = new HashSet<>();
            for (var cluster : clusters) {
                var resp = client.listTasks(b -> b.cluster(cluster).desiredStatus(DesiredStatus.RUNNING));
                for (var arn : resp.taskArns()) {
                    int slash = arn.lastIndexOf('/');
                    if (slash > 0) {
                        ids.add(arn.substring(slash + 1));
                    }
                }
            }
            return ids;
        }
    }

    private boolean isLiveConsumer(String consumerName, String group, Map<String, Set<String>> liveByRegion) {
        var prefix = group + "-";
        if (!consumerName.startsWith(prefix)) {
            return false;
        }
        var rest = consumerName.substring(prefix.length());
        int lastDash = rest.lastIndexOf('-');
        if (lastDash <= 0) {
            return false;
        }
        var region = rest.substring(0, lastDash);
        var suffix = rest.substring(lastDash + 1);
        var live = liveByRegion.getOrDefault(region, Set.of());
        return live.contains(suffix) || live.stream().anyMatch(id -> id.startsWith(suffix));
    }

    private void ackAllPendingFor(String stream, String group, String consumer) {
        PendingMessages pending = redisTemplate.opsForStream()
            .pending(stream, Consumer.from(group, consumer));
        if (pending == null || pending.isEmpty()) {
            return;
        }
        var ids = pending.stream().map(PendingMessage::getIdAsString).toArray(String[]::new);
        long acked = redisTemplate.opsForStream().acknowledge(stream, group, ids);
        log.warn("watchdog orphan ack: stream={} group={} consumer={} acked={}",
            stream, group, consumer, acked);
    }

    private record TaskRef(String region, String cluster, String taskId) {}

    private record StreamGroup(String stream, String group) {}
}
