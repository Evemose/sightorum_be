package com.rorm.ml.restate;

import com.rorm.DurableFuture;
import com.rorm.StepJournal;
import com.rorm.ml.restate.RestateDurableFuture.Delegated;
import com.rorm.ml.restate.RestateDurableFuture.Resolved;
import dev.restate.sdk.ObjectContext;
import dev.restate.sdk.common.StateKey;
import lombok.SneakyThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class RestateStepJournal implements StepJournal {

    /**
     * Fanout scope: when bound, nested {@code run}/{@code runAsync} calls
     * use a scoped ordinal counter and prefix instead of the root ones.
     * This ensures that journal entry names inside a fanout branch are
     * deterministic and scoped to that branch, enabling correct replay
     * of nested fanout.
     *
     * <p>Uses Java 25 {@link ScopedValue} — values are inherited by
     * nested calls without ThreadLocal overhead, and automatically
     * unbound when the scope exits.
     */
    @SuppressWarnings("preview")
    public static final ScopedValue<AtomicInteger> SCOPED_ORDINAL = ScopedValue.newInstance();
    @SuppressWarnings("preview")
    public static final ScopedValue<String> SCOPED_PREFIX = ScopedValue.newInstance();
    private final ObjectContext ctx;
    private final AtomicInteger callOrdinal;

    public RestateStepJournal(ObjectContext ctx) {
        this.ctx = ctx;
        this.callOrdinal = new AtomicInteger(0);
    }

    public ObjectContext context() {
        return ctx;
    }

    @Override
    public <T> DurableFuture<T> runAsync(String stepName, Class<T> resultType, Supplier<T> action) {
        var qualifiedName = qualifiedName(stepName);
        var cached = ctx.get(StateKey.of(qualifiedName, resultType));
        if (cached.isPresent()) {
            return new Resolved<>(resultType.cast(cached.get()), UUID.randomUUID().toString());
        }
        var future = ctx.runAsync(qualifiedName, resultType, action::get);
        return new Delegated<>(
            future.map(result -> {
                ctx.set(StateKey.of(qualifiedName, resultType), result);
                return result;
            }),
            UUID.randomUUID().toString()
        );
    }

    /**
     * Builds a deterministic qualified name for a journal entry,
     * incorporating the fanout scope prefix when present.
     */
    private String qualifiedName(String stepName) {
        var ordinal = currentOrdinal().getAndIncrement();
        var prefix = currentPrefix();
        return prefix.isEmpty()
            ? ordinal + ":" + stepName
            : prefix + "/" + ordinal + ":" + stepName;
    }

    /**
     * Returns the current ordinal counter — scoped if inside a fanout,
     * otherwise the root counter.
     */
    private AtomicInteger currentOrdinal() {
        return SCOPED_ORDINAL.isBound() ? SCOPED_ORDINAL.get() : callOrdinal;
    }

    /**
     * Returns the current step prefix, or empty string if at root level.
     */
    private static String currentPrefix() {
        return SCOPED_PREFIX.isBound() ? SCOPED_PREFIX.get() : "";
    }

    @Override
    public UUID randomUUID() {
        return run("random", UUID.class, UUID::randomUUID);
    }

    @SneakyThrows
    @Override
    public <T> T run(String stepName, Class<T> resultType, Supplier<T> action) {
        var qualifiedName = qualifiedName(stepName);
        var stateKey = StateKey.of(qualifiedName, resultType);
        var cached = ctx.get(stateKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        var result = ctx.run(qualifiedName, resultType, action::get);
        ctx.set(stateKey, result);
        return result;
    }

    @Override
    public <T> DurableFuture<T> awakeable(Class<T> type) {
        var awakeable = ctx.awakeable(type);
        return new Delegated<>(awakeable, awakeable.id());
    }

    /**
     * Restate Supplier-fanout: runs each branch inline on the handler thread
     * with its own scoped prefix/ordinal. Branches DO NOT run in parallel —
     * wrapping each branch in {@code ctx.runAsync} would make the closure a
     * Run side-effect, which is incompatible with branches that journal their
     * own work (nested {@code ctx.run}, awakeable {@code await}, etc.).
     * <p>
     * For real parallel sub-invocation fanout under Restate, use
     * {@link com.rorm.DurableRuntime#fanout} which dispatches each branch as
     * its own Restate invocation with its own journal.
     */
    @Override
    public <T> List<T> fanout(String stepPrefix, Class<T> resultType, List<Supplier<T>> actions) {
        if (actions.isEmpty()) {
            return List.of();
        }
        var parentPrefix = currentPrefix();
        var scopedBase = parentPrefix.isEmpty() ? stepPrefix : parentPrefix + "/" + stepPrefix;

        var results = new ArrayList<T>(actions.size());
        for (var i = 0; i < actions.size(); i++) {
            var branchPrefix = scopedBase + ":" + i;
            var branchOrdinal = new AtomicInteger(0);
            final var action = actions.get(i);
            results.add(ScopedValue.where(StepJournal.CURRENT, this)
                .where(SCOPED_ORDINAL, branchOrdinal)
                .where(SCOPED_PREFIX, branchPrefix)
                .call(action::get));
        }
        return results;
    }
}