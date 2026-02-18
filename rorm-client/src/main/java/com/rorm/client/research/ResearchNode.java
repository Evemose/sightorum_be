package com.rorm.client.research;

import com.rorm.ai.swarm.dto.StepRef;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.*;

@Getter
@Entity
@Table(name = "research_nodes")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "status")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public abstract class ResearchNode {

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "research_id", nullable = false)
    private Research research;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResearchNodeType nodeType;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "branch_id")
    private String branchId;

    @Column(name = "step_id")
    private String stepId;

    @Column(name = "previous_step_id")
    private String previousStepId;

    @Type(JsonType.class)
    @Column(name = "dependency_refs", columnDefinition = "jsonb", nullable = false)
    private List<StepRef> dependencyRefs;

    @ManyToMany
    @JoinTable(
        name = "research_node_dependencies",
        joinColumns = @JoinColumn(name = "node_id"),
        inverseJoinColumns = @JoinColumn(name = "dependency_id")
    )
    private Set<ResearchNode> dependencies = new HashSet<>();

    @CreatedDate
    private Instant createdAt;

    protected ResearchNode(
        Research research,
        ResearchNodeType nodeType,
        String nodeId,
        String branchId,
        String stepId,
        String previousStepId,
        List<StepRef> dependencyRefs,
        Set<ResearchNode> dependencies
    ) {
        this.research = Objects.requireNonNull(research, "research must not be null");
        this.nodeType = Objects.requireNonNull(nodeType, "nodeType must not be null");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.branchId = branchId;
        this.stepId = stepId;
        this.previousStepId = previousStepId;
        this.dependencyRefs = List.copyOf(Objects.requireNonNull(dependencyRefs, "dependencyRefs must not be null"));
        this.dependencies = new HashSet<>(Objects.requireNonNull(dependencies, "dependencies must not be null"));
    }
}
