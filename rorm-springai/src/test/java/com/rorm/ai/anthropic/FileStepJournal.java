package com.rorm.ai.anthropic;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.CompletableDurableFuture;
import com.rorm.DurableFuture;
import com.rorm.StepJournal;
import lombok.SneakyThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@SuppressWarnings("preview")
class FileStepJournal implements StepJournal {

    private final Path dir;
    private final ObjectMapper mapper;
    private int stepIndex = 0;

    FileStepJournal(Path dir) {
        this.dir = dir;
        this.mapper = ObjectMappers.jsonMapper();
    }

    public <T> T run(String stepName, TypeReference<T> typeRef, Supplier<T> action) {
        return null;
    }

    @SneakyThrows
    @Override
    public <T> T run(String stepName, Class<T> resultType, Supplier<T> action) {
        var idx = stepIndex++;
        var file = dir.resolve(idx + ".json");
        if (Files.exists(file)) {
            return mapper.readValue(file.toFile(), resultType);
        }
        var result = action.get();
        mapper.writeValue(file.toFile(), result);
        return result;
    }

    @SneakyThrows
    @Override
    public <T> T run(String stepName, Supplier<T> action) {
        var idx = stepIndex++;
        var file = dir.resolve(idx + ".json");
        if (Files.exists(file)) {
            return mapper.readValue(file.toFile(), new TypeReference<>() {});
        }
        var result = action.get();
        mapper.writeValue(file.toFile(), result);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> fanout(String stepPrefix, Class<T> resultType, List<Supplier<T>> actions) {
        if (actions.isEmpty()) {
            return List.of();
        }
        var futures = new DurableFuture[actions.size()];
        for (int i = 0; i < actions.size(); i++) {
            futures[i] = runAsync(stepPrefix + ":" + i, resultType, actions.get(i));
        }
        DurableFuture.all(futures).await();
        var results = new ArrayList<T>(actions.size());
        for (var f : futures) results.add(((DurableFuture<T>) f).await());
        return results;
    }

    @Override
    public <T> DurableFuture<T> runAsync(String stepName, Supplier<T> action) {
        return CompletableDurableFuture.by(CompletableFuture.supplyAsync(action));
    }

    @Override
    public <T> DurableFuture<T> awakeable(Class<T> type) {
        return CompletableDurableFuture.pending();
    }

    @Override
    public UUID randomUUID() {
        return UUID.randomUUID();
    }

    @Override
    public <T> DurableFuture<T> runAsync(String stepName, Class<T> resultType, Supplier<T> action) {
        return CompletableDurableFuture.by(CompletableFuture.supplyAsync(action));
    }

    @SneakyThrows
    long entryCount() {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).count();
        }
    }
}
