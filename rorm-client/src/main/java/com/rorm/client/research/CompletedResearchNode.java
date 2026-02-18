package com.rorm.client.research;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rorm.ai.swarm.dto.StepRef;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Getter
@Entity
@DiscriminatorValue("COMPLETED")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompletedResearchNode extends ResearchNode {

    @NotNull
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private ObjectNode payload;

    @NotNull
    @Column(columnDefinition = "text", nullable = false)
    private String rawResponse;

    public CompletedResearchNode(
        Research research,
        ResearchNodeType nodeType,
        String nodeId,
        String branchId,
        String stepId,
        String previousStepId,
        List<StepRef> dependencyRefs,
        Set<ResearchNode> dependencies,
        ObjectNode payload,
        String rawResponse
    ) {
        super(research, nodeType, nodeId, branchId, stepId, previousStepId, dependencyRefs, dependencies);
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.rawResponse = Objects.requireNonNull(rawResponse, "rawResponse must not be null");
    }
}
