package com.rorm.ml.stream;

public class TrainingFailedException extends RuntimeException {

    private final TrainingEvent event;

    public TrainingFailedException(TrainingEvent event) {
        super("Training %s failed: %s".formatted(event.trainingId(), event.error()));
        this.event = event;
    }

    public TrainingEvent event() {
        return event;
    }
}
