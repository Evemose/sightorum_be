package com.rorm.ai.swarm.executor;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCoalescingGateTest {

    private static final Duration WINDOW = Duration.ofMillis(200);

    private AgentCoalescingGate gate;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        gate = new AgentCoalescingGate(new CoalescingProperties(WINDOW, List.of()));
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void windowDisabled_runsImmediately() {
        var disabled = new AgentCoalescingGate(new CoalescingProperties(Duration.ZERO, List.of()));
        var start = System.nanoTime();
        var result = disabled.gate("any", signal -> {
            signal.run();
            return "ok";
        });
        var elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result).isEqualTo("ok");
        assertThat(elapsedMs).isLessThan(50);
    }

    @Test
    void singleAgent_releasedAfterWindow() {
        var start = System.nanoTime();
        var result = gate.gate("role-A", signal -> {
            signal.run();
            return "ok";
        });
        var elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result).isEqualTo("ok");
        assertThat(elapsedMs).isBetween(WINDOW.toMillis() - 50, WINDOW.toMillis() * 4);
    }

    @Test
    void twoSameRole_leaderRunsFirst_followerWaitsForFirstTokenSignal() {
        var leaderHasSignal = new CompletableFuture<Runnable>();
        var followerEnteredWork = new CompletableFuture<Long>();
        var leaderResult = submit("role-A", signal -> {
            leaderHasSignal.complete(signal);
            // simulate streaming latency between leader's release and its first token
            sleepQuietly(150);
            signal.run();
            return "leader";
        });
        awaitEnrollment("role-A", 1);
        var followerResult = submit("role-A", signal -> {
            followerEnteredWork.complete(System.nanoTime());
            return "follower";
        });
        awaitEnrollment("role-A", 2);

        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(leaderHasSignal::isDone);
        assertThat(leaderHasSignal.getNow(null)).isNotNull();
        // follower must NOT have entered work yet — leader hasn't fired the signal
        assertThat(followerEnteredWork).isNotDone();

        Awaitility.await().atMost(2, TimeUnit.SECONDS)
            .until(() -> leaderResult.isDone() && followerResult.isDone());
        assertThat(leaderResult.getNow(null)).isEqualTo("leader");
        assertThat(followerResult.getNow(null)).isEqualTo("follower");
    }

    private CompletableFuture<String> submit(String role,
                                             java.util.function.Function<Runnable, String> work) {
        var fired = new AtomicBoolean();
        return CompletableFuture.supplyAsync(() -> gate.gate(role, signal -> {
            // wrap to make signal idempotent across test work bodies that may call it more than once
            return work.apply(() -> {
                if (fired.compareAndSet(false, true)) {
                    signal.run();
                }
            });
        }), pool);
    }

    private void awaitEnrollment(String role, int expectedCount) {
        Awaitility.await().atMost(1, TimeUnit.SECONDS)
            .pollInterval(Duration.ofMillis(1))
            .until(() -> gate.enrolledCount(role) >= expectedCount);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void slidingExtension_repeatedArrivalsKeepWindowOpen() {
        var entered = new ConcurrentLinkedQueue<Long>();
        var futures = new java.util.ArrayList<CompletableFuture<String>>();
        var firstArrival = System.nanoTime();
        // Fire 4 arrivals at WINDOW/2 apart — each should extend the window
        for (int i = 0; i < 4; i++) {
            int idx = i;
            futures.add(submit("role-A", signal -> {
                entered.add(System.nanoTime());
                if (idx == 0) {
                    signal.run();   // leader fires immediately
                }
                return "ok-" + idx;
            }));
            awaitEnrollment("role-A", idx + 1);
            if (i < 3) {
                sleepQuietly(WINDOW.toMillis() / 2);
            }
        }
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
            .until(() -> futures.stream().allMatch(CompletableFuture::isDone));
        assertThat(futures).allSatisfy(f -> assertThat(f.getNow(null)).startsWith("ok-"));
        var firstEntered = entered.peek();
        assertThat(firstEntered).isNotNull();
        // Without sliding extension: leader would release at t=WINDOW (200ms).
        // With sliding extension: last arrival at t=3*(WINDOW/2)=300ms re-schedules
        // close to t=300+WINDOW=500ms, so leader enters at ~500ms.
        // Assert ≥ 1.5*WINDOW to robustly prove extension actually took effect.
        var elapsedToFirstEnteredMs = (firstEntered - firstArrival) / 1_000_000;
        assertThat(elapsedToFirstEnteredMs)
            .as("leader must wait for sliding window to expire after final arrival")
            .isGreaterThan((long) (WINDOW.toMillis() * 1.5));
    }

    @Test
    void differentRoles_independent() {
        var aEntered = new CompletableFuture<Long>();
        var bEntered = new CompletableFuture<Long>();
        var aFuture = submit("role-A", signal -> {
            aEntered.complete(System.nanoTime());
            signal.run();
            return "A";
        });
        var bFuture = submit("role-B", signal -> {
            bEntered.complete(System.nanoTime());
            signal.run();
            return "B";
        });
        Awaitility.await().atMost(2, TimeUnit.SECONDS)
            .until(() -> aFuture.isDone() && bFuture.isDone());
        assertThat(aFuture.getNow(null)).isEqualTo("A");
        assertThat(bFuture.getNow(null)).isEqualTo("B");
        var spread = Math.abs(aEntered.getNow(0L) - bEntered.getNow(0L)) / 1_000_000;
        assertThat(spread)
            .as("different roles should not gate each other — both release after own window")
            .isLessThan(WINDOW.toMillis());
    }

    @Test
    void leaderFirstTokenSignal_releasesAllFollowers() {
        var leaderReady = new CompletableFuture<Runnable>();
        var followers = new ConcurrentLinkedQueue<Long>();
        var leader = submit("role-A", signal -> {
            leaderReady.complete(signal);
            sleepQuietly(300);
            signal.run();
            return "leader";
        });
        awaitEnrollment("role-A", 1);
        var f1 = submit("role-A", signal -> {
            followers.add(System.nanoTime());
            return "f1";
        });
        awaitEnrollment("role-A", 2);
        var f2 = submit("role-A", signal -> {
            followers.add(System.nanoTime());
            return "f2";
        });
        awaitEnrollment("role-A", 3);
        var f3 = submit("role-A", signal -> {
            followers.add(System.nanoTime());
            return "f3";
        });
        awaitEnrollment("role-A", 4);

        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(leaderReady::isDone);

        Awaitility.await().atMost(3, TimeUnit.SECONDS)
            .until(() -> followers.size() == 3 && leader.isDone());
        Awaitility.await().atMost(1, TimeUnit.SECONDS)
            .until(() -> f1.isDone() && f2.isDone() && f3.isDone());
        assertThat(List.of(f1, f2, f3))
            .allSatisfy(f -> assertThat(f.getNow(null)).startsWith("f"));
        var releaseSpreadMs = (max(followers) - min(followers)) / 1_000_000;
        assertThat(releaseSpreadMs)
            .as("followers should release together after leader's signal")
            .isLessThan(100);
    }

    private static long max(ConcurrentLinkedQueue<Long> q) {
        return q.stream().mapToLong(Long::longValue).max().orElseThrow();
    }

    private static long min(ConcurrentLinkedQueue<Long> q) {
        return q.stream().mapToLong(Long::longValue).min().orElseThrow();
    }

    @Test
    void followerSignalIsNoOp_noEarlyReleaseFromFollower() {
        var leaderEntered = new CompletableFuture<Long>();
        var leaderShouldFinish = new CompletableFuture<Void>();
        var followerEntered = new CompletableFuture<Long>();
        var leader = submit("role-A", signal -> {
            leaderEntered.complete(System.nanoTime());
            // intentionally do not fire signal until told
            leaderShouldFinish.join();
            signal.run();
            return "leader";
        });
        awaitEnrollment("role-A", 1);
        var follower = submit("role-A", signal -> {
            followerEntered.complete(System.nanoTime());
            signal.run();   // follower's signal must be NO_OP — should not unblock anyone
            return "follower";
        });
        awaitEnrollment("role-A", 2);

        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(leaderEntered::isDone);
        // follower must still be parked because leader hasn't signalled
        sleepQuietly(150);
        assertThat(followerEntered).isNotDone();

        leaderShouldFinish.complete(null);
        Awaitility.await().atMost(2, TimeUnit.SECONDS)
            .until(() -> leader.isDone() && follower.isDone());
        assertThat(leader.getNow(null)).isEqualTo("leader");
        assertThat(follower.getNow(null)).isEqualTo("follower");
    }
}
