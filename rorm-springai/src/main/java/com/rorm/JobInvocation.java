package com.rorm;

/**
 * A submission target: which Restate session key to dispatch under, and the
 * JobSpec describing the bean+method+args to execute. Used by
 * {@link DurableRuntime#fanout} to parallelize sub-invocations.
 */
public record JobInvocation(String sessionId, JobSpec spec) {
}
