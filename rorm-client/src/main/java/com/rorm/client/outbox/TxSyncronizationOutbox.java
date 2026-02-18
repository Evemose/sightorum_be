package com.rorm.client.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static com.rorm.client.config.ScopesConfig.TRANSACTION_SCOPE;

@Slf4j
@Component
@Scope(scopeName = TRANSACTION_SCOPE, proxyMode = ScopedProxyMode.TARGET_CLASS)
class TxSyncronizationOutbox implements Outbox {

    private final List<Runnable> tasks = new ArrayList<>();

    public TxSyncronizationOutbox() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Runnable task : tasks) {
                    try {
                        task.run();
                    } catch (Exception e) {
                        log.error("Outbox task failed: {}", e.getMessage(), e);
                    }
                }
            }
        });
    }

    @Override
    public void runOnCommit(Runnable task) {
        tasks.add(task);
    }

}
