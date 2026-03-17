package com.rorm.ai.anthropic;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.durable.StepJournal;
import lombok.SneakyThrows;

import java.nio.file.Files;
import java.nio.file.Path;
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
    long entryCount() {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).count();
        }
    }
}
