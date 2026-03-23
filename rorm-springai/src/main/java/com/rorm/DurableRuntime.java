package com.rorm;

public interface DurableRuntime {

    Object submit(String sessionId, JobSpec spec);

    <T> AwakableHandle<T> handle(String awakableId, Class<T> resultType);
}
