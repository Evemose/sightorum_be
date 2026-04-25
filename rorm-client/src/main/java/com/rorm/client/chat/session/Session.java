package com.rorm.client.chat.session;

import jakarta.persistence.*;
import lombok.*;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * User-facing conversation aggregate. Owns a primary copilot chat (via
 * {@link #primaryChatId}), zero-or-more {@link SessionAnalysis} runs, and
 * zero-or-more import jobs linked by session_id. Internal chats spawned by
 * swarm agents etc. are NOT sessions and have their own ID schemes.
 */
@Getter
@Setter
@Entity
@Table(name = "sessions")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Session {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    @Nullable
    @Column(length = 500)
    private String title;

    @Nullable
    private String schemaName;

    @Column(nullable = false)
    private String primaryChatId;

    @CreatedDate
    @Column(nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    public Session(String id, String primaryChatId, @Nullable String schemaName) {
        this.id = id;
        this.primaryChatId = primaryChatId;
        this.schemaName = schemaName;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
