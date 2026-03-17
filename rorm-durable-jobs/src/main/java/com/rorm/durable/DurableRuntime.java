package com.rorm.durable;

public interface DurableRuntime {

    Object submit(String sessionId, JobSpec spec);
}
