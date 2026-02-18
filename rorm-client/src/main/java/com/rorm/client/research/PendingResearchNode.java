package com.rorm.client.research;

import com.rorm.ai.swarm.dto.StepRef;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Getter
@Entity
@DiscriminatorValue("PENDING")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendingResearchNode extends ResearchNode {

    @NotBlank
    @Column(name = "progress_node_id", nullable = false)
    private String progressNodeId;

    public PendingResearchNode(
        Research research,
        String progressNodeId,
        ResearchNodeType nodeType,
        String nodeId,
        String branchId,
        String stepId,
        String previousStepId,
        List<StepRef> dependencyRefs,
        Set<ResearchNode> dependencies
    ) {
        super(research, nodeType, nodeId, branchId, stepId, previousStepId, dependencyRefs, dependencies);
        this.progressNodeId = Objects.requireNonNull(progressNodeId, "progressNodeId must not be null");
    }
}
