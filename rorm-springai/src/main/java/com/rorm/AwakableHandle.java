package com.rorm;

public interface AwakableHandle<T> {

    void resolve(T result);

    void reject(String reason);

}
