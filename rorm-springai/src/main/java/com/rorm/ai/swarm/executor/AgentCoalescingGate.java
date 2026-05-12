package com.rorm.ai.swarm.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Cost-optimisation gate: coalesces concurrent agent invocations of the
 * same role so peers share one warmed-up prompt cache. A slot per role
 * collects arrivals; every new arrival resets a sliding {@code window}
 * timer. When the window finally elapses without further arrivals the
 * first arrival is released as the {@code leader}; the rest stay parked
 * until the leader emits its first response token, then run in parallel.
 * <p>
 * Layered functionally over the work: {@link #gate(String, Function)}
 * receives a {@code Function<Runnable, T>} so the caller can call the
 * supplied {@link Runnable} when its first token arrives, signalling
 * followers.
 */
@Slf4j
@Component
public class AgentCoalescingGate {

    private static final Runnable NO_OP = () -> {
    };

    private final Duration window;
    private final Set<String> excludedKinds;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<String, Slot> slots = new ConcurrentHashMap<>();

    public AgentCoalescingGate(CoalescingProperties props) {
        this.window = props.window();
        this.excludedKinds = Set.copyOf(props.excludedRoles());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "agent-coalescing-gate");
            t.setDaemon(true);
            return t;
        });
    }

    public <T> T gate(String role, Function<Runnable, T> work) {
        if (excludedKinds.contains(role)) {
            return work.apply(NO_OP);
        }
        if (window.isZero() || window.isNegative()) {
            return work.apply(NO_OP);
        }
        var assignment = enrollAndAwait(role);
        return assignment.isLeader()
            ? work.apply(() -> assignment.firstTokenSignal().complete(null))
            : runFollower(assignment.firstTokenSignal(), work);
    }

    private Assignment enrollAndAwait(String role) {
        var ticket = new Ticket();
        slots.compute(role, (key, existing) -> {
            var slot = (existing != null && existing.isOpen()) ? existing : new Slot(key);
            slot.add(ticket);
            slot.reschedule(scheduler, window, this::closeSlot);
            return slot;
        });
        return ticket.awaitAssignment();
    }

    private <T> T runFollower(CompletableFuture<Void> firstToken, Function<Runnable, T> work) {
        awaitQuietly(firstToken);
        return work.apply(NO_OP);
    }

    private void closeSlot(String role) {
        slots.computeIfPresent(role, (k, slot) -> {
            slot.close();
            log.info("[coalescing] released slot role={} participants={}", k, slot.size());
            return null;
        });
    }

    private static void awaitQuietly(CompletableFuture<?> f) {
        try {
            f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Coalescing gate interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Coalescing gate failed", e.getCause());
        }
    }

    private record Assignment(boolean isLeader, CompletableFuture<Void> firstTokenSignal) {
    }

    private static final class Ticket {
        final CompletableFuture<Assignment> assigned = new CompletableFuture<>();

        void assign(boolean leader, CompletableFuture<Void> firstToken) {
            assigned.complete(new Assignment(leader, firstToken));
        }

        Assignment awaitAssignment() {
            try {
                return assigned.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Coalescing gate interrupted", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Coalescing gate failed", e.getCause());
            }
        }
    }

    private static final class Slot {

        private final String role;
        private final Object lock = new Object();
        private final List<Ticket> tickets = new ArrayList<>();
        private final CompletableFuture<Void> firstTokenSignal = new CompletableFuture<>();
        private ScheduledFuture<?> closeTask;
        private boolean closed;

        Slot(String role) {
            this.role = role;
        }

        boolean isOpen() {
            synchronized (lock) {
                return !closed;
            }
        }

        int size() {
            synchronized (lock) {
                return tickets.size();
            }
        }

        void add(Ticket ticket) {
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("Slot already closed for role=" + role);
                }
                tickets.add(ticket);
            }
        }

        void reschedule(ScheduledExecutorService scheduler, Duration window,
                        java.util.function.Consumer<String> onClose) {
            synchronized (lock) {
                if (closeTask != null) {
                    closeTask.cancel(false);
                }
                closeTask = scheduler.schedule(
                    () -> onClose.accept(role),
                    window.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        void close() {
            synchronized (lock) {
                closed = true;
                for (int i = 0; i < tickets.size(); i++) {
                    tickets.get(i).assign(i == 0, firstTokenSignal);
                }
            }
        }
    }
}
