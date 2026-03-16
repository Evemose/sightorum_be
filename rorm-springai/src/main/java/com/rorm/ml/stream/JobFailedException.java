package com.rorm.ml.stream;

public class JobFailedException extends RuntimeException {

    private final JobEvent event;

    public JobFailedException(JobEvent event) {
        super("Job %s failed: %s".formatted(event.jobId(), event.error()));
        this.event = event;
    }

    public JobEvent event() {
        return event;
    }
}
