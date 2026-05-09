package com.rorm.client.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sts.StsClient;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class MlServiceStartup implements SmartLifecycle {

    private final EcsClient ecs;
    private final StsClient sts;
    private final IamClient iam;
    private final RestClient rest = RestClient.create();
    @Value("${rorm.ml.cluster}")
    private String cluster;
    @Value("${rorm.ml.service}")
    private String service;
    @Value("${rorm.ml.service-base-url}")
    private String albUrl;
    @Value("${rorm.ml.startup.timeout:PT4M}")
    private Duration timeout;
    @Value("${rorm.ml.startup.poll-interval:PT3S}")
    private Duration pollInterval;
    @Value("${rorm.ml.ping.poll-interval:PT1M}")
    private Duration pingInterval;
    @Value("${rorm.ml.startup.enabled:true}")
    private boolean enabled;
    private volatile boolean running;

    private Thread pingThread;

    @Override
    public int getPhase() {
        // Run early — before anything that depends on ML service
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public void start() {
        if (!enabled) {
            running = true;
            return;
        }

        logIdentityAndPermissions();
        log.info("Ensuring ML service is running...");
        ensureRunning();
        log.info("ML service is healthy");
        running = true;
        pingThread = Thread.ofVirtual().start(() -> {
            while (running) {
                if (!ping()) {
                    log.warn("ML service became unhealthy");
                }
                try {
                    Thread.sleep(pingInterval.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void logIdentityAndPermissions() {
        try {
            var caller = sts.getCallerIdentity();
            log.info("AWS identity: arn={} account={} userId={}",
                caller.arn(), caller.account(), caller.userId());

            var serviceArn = "arn:aws:ecs:eu-central-1:%s:service/%s/%s".formatted(
                caller.account(), cluster, service);
            try {
                var sim = iam.simulatePrincipalPolicy(r -> r
                    .policySourceArn(caller.arn())
                    .actionNames("ecs:DescribeServices", "ecs:UpdateService")
                    .resourceArns(serviceArn));
                for (var result : sim.evaluationResults()) {
                    log.info("permission {} on {} = {}",
                        result.evalActionName(),
                        result.evalResourceName(),
                        result.evalDecisionAsString());
                }
            } catch (Exception e) {
                log.warn("simulate-principal-policy failed (likely missing iam:SimulatePrincipalPolicy on caller): {}",
                    e.getMessage());
                logAttachedPolicies(caller.arn());
            }
        } catch (Exception e) {
            log.error("Failed to query AWS identity (STS unreachable or no credentials): {}", e.getMessage());
        }
    }

    private void logAttachedPolicies(String callerArn) {
        try {
            if (callerArn.contains(":user/")) {
                var userName = callerArn.substring(callerArn.indexOf(":user/") + ":user/".length());
                var attached = iam.listAttachedUserPolicies(r -> r.userName(userName));
                var inline = iam.listUserPolicies(r -> r.userName(userName));
                log.info("user {} attached={} inline={}", userName,
                    attached.attachedPolicies().stream().map(p -> p.policyName()).toList(),
                    inline.policyNames());
            } else if (callerArn.contains(":assumed-role/")) {
                var roleName = callerArn.substring(callerArn.indexOf(":assumed-role/") + ":assumed-role/".length())
                    .split("/")[0];
                var attached = iam.listAttachedRolePolicies(r -> r.roleName(roleName));
                var inline = iam.listRolePolicies(r -> r.roleName(roleName));
                log.info("role {} attached={} inline={}", roleName,
                    attached.attachedPolicies().stream().map(p -> p.policyName()).toList(),
                    inline.policyNames());
            } else {
                log.info("caller {} is neither user nor assumed-role; skipping policy enumeration", callerArn);
            }
        } catch (Exception e) {
            log.warn("Could not enumerate attached policies (likely missing iam:List*): {}", e.getMessage());
        }
    }

    private void ensureRunning() {
        var desc = ecs.describeServices(r -> r
            .cluster(cluster)
            .services(service));

        var svc = desc.services().getFirst();
        if (svc.runningCount() > 0) {
            log.info("ML service already has {} running tasks", svc.runningCount());
            waitForHealthy();
            return;
        }

        log.info("No running tasks, setting desired count to 1...");

        waitForHealthy();
    }

    private boolean ping() {
        try {
            rest.get()
                .uri(albUrl + "/health")
                .retrieve()
                .body(String.class);
            return true;
        } catch (Exception e) {
            log.debug("ML service not ready yet: {}", e.getMessage());
            return false;
        }
    }

    private void waitForHealthy() {
        if (ping()) {
            return;
        }

        var deadline = Instant.now().plus(timeout);
        var spamWakeupThread = Thread.ofVirtual().name("ml-service-wakeup").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ecs.updateService(r -> r
                        .cluster(cluster)
                        .service(service)
                        .desiredCount(1));
                    Thread.sleep(30_000);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        while (Instant.now().isBefore(deadline)) {
            if (ping()) {
                spamWakeupThread.interrupt();
                return;
            }

            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for ML service");
            }
        }

        throw new IllegalStateException(
            "ML service did not become healthy within " + timeout);
    }

    @Override
    public void stop() {
        running = false;
        if (pingThread != null) {
            pingThread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}