package com.rorm.client.chat.session;

import jakarta.persistence.*;
import lombok.*;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "session_analyses")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SessionAnalysis {

    @Id
    @EqualsAndHashCode.Include
    private String runId;

    @Column(nullable = false)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisKind kind;

    @Nullable
    @Column(columnDefinition = "text")
    private String query;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisStatus status;

    @CreatedDate
    @Column(nullable = false)
    private Instant startedAt;

    @Nullable
    private Instant completedAt;

    public SessionAnalysis(String runId, String sessionId, AnalysisKind kind, @Nullable String query) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.kind = kind;
        this.query = query;
        this.status = AnalysisStatus.RUNNING;
    }

    public void markSucceeded() {
        this.status = AnalysisStatus.SUCCEEDED;
        this.completedAt = Instant.now();
    }

    public void markFailed() {
        this.status = AnalysisStatus.FAILED;
        this.completedAt = Instant.now();
    }
}
