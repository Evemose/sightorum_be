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

    @Override
    public <T> T run(String stepName, TypeReference<T> typeRef, Supplier<T> action) {
        return null;
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

    @SneakyThrows
    long entryCount() {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).count();
        }
    }
}
