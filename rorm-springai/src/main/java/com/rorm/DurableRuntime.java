package com.rorm;

public interface DurableRuntime {

    Object submit(String sessionId, JobSpec spec);
}
