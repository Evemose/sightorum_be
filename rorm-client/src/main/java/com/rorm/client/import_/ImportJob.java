package com.rorm.client.import_;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "import_jobs")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ImportJob {

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportJobStatus status;

    @Column(nullable = false)
    private String targetSchema;

    @Column(columnDefinition = "text")
    private String uploadDir;

    @CreatedDate
    private Instant startedAt;

    private Instant completedAt;

    private Long totalRows;

    private Long processedRows;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<ImportEventLog> eventLog = new ArrayList<>();

    public ImportJob(String targetSchema, String uploadDir) {
        this.status = ImportJobStatus.QUEUED;
        this.targetSchema = targetSchema;
        this.uploadDir = uploadDir;
        this.processedRows = 0L;
    }

    public void markInProgress() {
        this.status = ImportJobStatus.IN_PROGRESS;
    }

    public void markCompleted(long totalRows) {
        this.status = ImportJobStatus.COMPLETED;
        this.totalRows = totalRows;
        this.processedRows = totalRows;
        this.completedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status = ImportJobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
    }

    public void updateProgress(long processedRows, Long totalRows) {
        this.processedRows = processedRows;
        if (totalRows != null) {
            this.totalRows = totalRows;
        }
    }

    public void appendEvent(ImportEventLog event) {
        this.eventLog.add(event);
    }
}
