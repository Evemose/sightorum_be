package com.rorm.ai.swarm;

import lombok.SneakyThrows;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class ContentHash {

    private ContentHash() {
    }

    @SneakyThrows
    public static UUID of(Map<String, String> attributes) {
        var sorted = new TreeMap<>(attributes);
        var digest = MessageDigest.getInstance("SHA-256");
        for (var entry : sorted.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        var buf = ByteBuffer.wrap(digest.digest());
        return new UUID(buf.getLong(), buf.getLong());
    }
}
