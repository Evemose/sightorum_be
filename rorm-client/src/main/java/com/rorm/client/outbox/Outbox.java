package com.rorm.client.outbox;

public interface Outbox {
    void runOnCommit(Runnable task);
}
