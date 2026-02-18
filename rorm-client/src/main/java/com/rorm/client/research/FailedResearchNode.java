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
@DiscriminatorValue("FAILED")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FailedResearchNode extends ResearchNode {

    @NotBlank
    @Column(name = "error_message", columnDefinition = "text", nullable = false)
    private String errorMessage;

    public FailedResearchNode(
        Research research,
        ResearchNodeType nodeType,
        String nodeId,
        String branchId,
        String stepId,
        String previousStepId,
        List<StepRef> dependencyRefs,
        Set<ResearchNode> dependencies,
        String errorMessage
    ) {
        super(research, nodeType, nodeId, branchId, stepId, previousStepId, dependencyRefs, dependencies);
        this.errorMessage = Objects.requireNonNull(errorMessage, "errorMessage must not be null");
    }
}
