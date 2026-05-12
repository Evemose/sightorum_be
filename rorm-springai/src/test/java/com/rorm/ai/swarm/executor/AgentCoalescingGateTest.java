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
    void twoSameRole_leaderRunsFirst_followerWaitsForFirstTokenSignal() throws Exception {
        var leaderHasSignal = new CompletableFuture<Runnable>();
        var followerEnteredWork = new CompletableFuture<Long>();
        var leaderResult = submit("role-A", signal -> {
            leaderHasSignal.complete(signal);
            // simulate streaming latency between leader's release and its first token
            sleepQuietly(150);
            signal.run();
            return "leader";
        });
        sleepQuietly(20);
        var followerResult = submit("role-A", signal -> {
            followerEnteredWork.complete(System.nanoTime());
            return "follower";
        });

        var signal = leaderHasSignal.get(2, TimeUnit.SECONDS);
        assertThat(signal).isNotNull();
        // follower must NOT have entered work yet — leader hasn't fired the signal
        assertThat(followerEnteredWork).isNotDone();

        assertThat(leaderResult.get(2, TimeUnit.SECONDS)).isEqualTo("leader");
        assertThat(followerResult.get(2, TimeUnit.SECONDS)).isEqualTo("follower");
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

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void slidingExtension_repeatedArrivalsKeepWindowOpen() throws Exception {
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
            sleepQuietly(WINDOW.toMillis() / 2);
        }
        for (var f : futures) {
            assertThat(f.get(5, TimeUnit.SECONDS)).startsWith("ok-");
        }
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
    void differentRoles_independent() throws Exception {
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
        assertThat(aFuture.get(2, TimeUnit.SECONDS)).isEqualTo("A");
        assertThat(bFuture.get(2, TimeUnit.SECONDS)).isEqualTo("B");
        var spread = Math.abs(aEntered.get() - bEntered.get()) / 1_000_000;
        assertThat(spread)
            .as("different roles should not gate each other — both release after own window")
            .isLessThan(WINDOW.toMillis());
    }

    @Test
    void leaderFirstTokenSignal_releasesAllFollowers() throws Exception {
        var leaderReady = new CompletableFuture<Runnable>();
        var followers = new ConcurrentLinkedQueue<Long>();
        var leader = submit("role-A", signal -> {
            leaderReady.complete(signal);
            sleepQuietly(300);
            signal.run();
            return "leader";
        });
        // 3 followers, all should be parked until leader signals
        var f1 = submit("role-A", signal -> {
            followers.add(System.nanoTime());
            return "f1";
        });
        sleepQuietly(20);
        var f2 = submit("role-A", signal -> {
            followers.add(System.nanoTime());
            return "f2";
        });
        sleepQuietly(20);
        var f3 = submit("role-A", signal -> {
            followers.add(System.nanoTime());
            return "f3";
        });

        leaderReady.get(2, TimeUnit.SECONDS);

        Awaitility.await().atMost(3, TimeUnit.SECONDS)
            .until(() -> followers.size() == 3 && leader.isDone());
        assertThat(List.of(f1, f2, f3))
            .allSatisfy(f -> assertThat(f.get(1, TimeUnit.SECONDS)).startsWith("f"));
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
    void followerSignalIsNoOp_noEarlyReleaseFromFollower() throws Exception {
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
        sleepQuietly(20);
        var follower = submit("role-A", signal -> {
            followerEntered.complete(System.nanoTime());
            signal.run();   // follower's signal must be NO_OP — should not unblock anyone
            return "follower";
        });

        leaderEntered.get(2, TimeUnit.SECONDS);
        // follower must still be parked because leader hasn't signalled
        sleepQuietly(150);
        assertThat(followerEntered).isNotDone();

        leaderShouldFinish.complete(null);
        assertThat(leader.get(2, TimeUnit.SECONDS)).isEqualTo("leader");
        assertThat(follower.get(2, TimeUnit.SECONDS)).isEqualTo("follower");
    }
}
