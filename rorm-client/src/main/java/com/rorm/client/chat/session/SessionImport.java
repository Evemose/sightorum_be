package com.rorm.client.chat.session;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.time.Instant;

/**
 * Join row linking a session to an import job. Sessions own this side of the
 * relation; the {@code import_jobs} table has no awareness of sessions.
 */
@Getter
@Setter
@Entity
@Table(name = "session_imports")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(SessionImport.Key.class)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SessionImport {

    @Id
    @EqualsAndHashCode.Include
    private String jobId;

    @Id
    @EqualsAndHashCode.Include
    private String sessionId;

    @CreatedDate
    @Column(nullable = false)
    private Instant linkedAt;

    public SessionImport(String jobId, String sessionId) {
        this.jobId = jobId;
        this.sessionId = sessionId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String jobId;
        private String sessionId;
    }
}
