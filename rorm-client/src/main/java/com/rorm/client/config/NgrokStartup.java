package com.rorm.client.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@Profile("dev")
public class NgrokStartup implements SmartLifecycle {

    private final Thread thread = Thread.ofVirtual().unstarted(() -> {
        try {
            new ProcessBuilder().command(
                "ngrok",
                "http",
                "9081",
                "--url",
                "unsiding-sparrowless-chaya.ngrok-free.dev"
            ).inheritIO().start();
        } catch (IOException e) {
            log.warn("Failed to run ngrok tunnel", e);
            throw new RuntimeException(e);
        }
    });

    @Override
    public int getPhase() {
        // Run early — before anything that depends on restate
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public void start() {
        thread.start();
        try {
            Thread.sleep(1000); // Give ngrok a moment to start up
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void stop() {
        thread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return thread.isAlive();
    }
}
