package com.rorm.client.import_;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "import_jobs", schema = "rorm_client")
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
}
