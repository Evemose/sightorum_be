package com.rorm.ml.peristence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rorm.ml.dto.AsyncJobRequest;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "ml_jobs")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MLJob {

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MLJobType jobType;

    @Column(nullable = false)
    private String schema;

    @Column(nullable = false)
    private String reason;

    @Nullable
    private String furtherInstructions;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private ObjectNode request;

    @CreatedDate
    private Instant createdAt;

    public static MLJob of(UUID jobId, MLJobType jobType, String schema,
                           AsyncJobRequest request, ObjectMapper mapper) {
        var job = new MLJob();
        job.jobId = jobId;
        job.jobType = jobType;
        job.schema = schema;
        job.reason = request.reason();
        job.furtherInstructions = request.furtherInstructions();
        job.request = mapper.convertValue(request, ObjectNode.class);
        return job;
    }

    @SuppressWarnings("unchecked")
    public <T extends AsyncJobRequest> T requestAs(ObjectMapper mapper) {
        try {
            return (T) mapper.treeToValue(request, jobType.requestType());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize job request for " + jobType, e);
        }
    }

    public MLJobInfo toInfo() {
        return new MLJobInfo(jobId, reason, furtherInstructions);
    }
}
