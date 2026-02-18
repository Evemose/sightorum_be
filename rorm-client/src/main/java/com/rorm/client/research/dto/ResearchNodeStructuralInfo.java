package com.rorm.client.research.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.rorm.ai.swarm.dto.StepRef;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ResearchNodeStructuralInfo.ScoutStructuralInfo.class, name = "SCOUT"),
    @JsonSubTypes.Type(value = ResearchNodeStructuralInfo.PlanStructuralInfo.class, name = "PLAN"),
    @JsonSubTypes.Type(value = ResearchNodeStructuralInfo.BranchStructuralInfo.class, name = "BRANCH"),
    @JsonSubTypes.Type(value = ResearchNodeStructuralInfo.StepStructuralInfo.class, name = "STEP"),
    @JsonSubTypes.Type(value = ResearchNodeStructuralInfo.AnalysisStructuralInfo.class, name = "ANALYSIS")
})
public sealed interface ResearchNodeStructuralInfo permits
    ResearchNodeStructuralInfo.ScoutStructuralInfo,
    ResearchNodeStructuralInfo.PlanStructuralInfo,
    ResearchNodeStructuralInfo.BranchStructuralInfo,
    ResearchNodeStructuralInfo.StepStructuralInfo,
    ResearchNodeStructuralInfo.AnalysisStructuralInfo {

    record ScoutStructuralInfo(String nodeId) implements ResearchNodeStructuralInfo {}

    record PlanStructuralInfo(String nodeId) implements ResearchNodeStructuralInfo {}

    record BranchStructuralInfo(String nodeId, String branchId) implements ResearchNodeStructuralInfo {}

    record StepStructuralInfo(
        String nodeId,
        String branchId,
        String stepId,
        String previousStepId,
        List<StepRef> dependencyRefs
    ) implements ResearchNodeStructuralInfo {}

    record AnalysisStructuralInfo(String nodeId) implements ResearchNodeStructuralInfo {}
}
