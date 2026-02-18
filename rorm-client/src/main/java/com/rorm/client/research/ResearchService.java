package com.rorm.client.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.swarm.Swarm;
import com.rorm.ai.swarm.SwarmConfig;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.dto.StepRef;
import com.rorm.client.metamodel.MetamodelRepository;
import com.rorm.client.outbox.Outbox;
import com.rorm.client.research.dto.ResearchNodeResponse;
import com.rorm.client.research.dto.ResearchNodeStructuralInfo;
import com.rorm.client.research.dto.ResearchResponse;
import com.rorm.client.research.dto.StartResearchRequest;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchService {

    private final ResearchRepository researchRepository;
    private final ResearchNodeRepository researchNodeRepository;
    private final MetamodelRepository metamodelRepository;
    private final ResearchEventPublisher eventPublisher;
    private final AiChatService aiChatService;
    private final SwarmConfig swarmConfig;
    private final Optional<VectorStore> vectorStore;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Outbox outbox;

    public ResearchResponse startResearch(StartResearchRequest request) {
        var metamodel = metamodelRepository.findBySchemaName(request.schemaName())
            .orElseThrow(() -> new EntityNotFoundException("Metamodel not found: " + request.schemaName()));

        var research = new Research(UUID.randomUUID().toString(), metamodel);
        research.setStatus(ResearchStatus.IN_PROGRESS);
        research = researchRepository.save(research);

        var researchId = research.getId();
        outbox.runOnCommit(() -> {
            var stream = request.mock()
                ? MockSwarm.research(request.query())
                : createSwarm(metamodel.getModelSpace()).research(request.query());
            stream
                .doOnNext(event -> handleEvent(researchId, event))
                .doOnComplete(() -> handleCompletion(researchId))
                .doOnError(error -> handleFailure(researchId, error))
                .subscribe();
        });

        return toResponse(research);
    }

    private Swarm createSwarm(com.rorm.metamodel.ModelSpace modelSpace) {
        var store = vectorStore.orElseThrow(() ->
            new IllegalStateException("VectorStore is required to run real swarm research"));
        return new Swarm(swarmConfig, aiChatService, modelSpace, store);
    }

    private void handleEvent(UUID researchId, SwarmEvent event) {
        switch (event) {
            case SwarmEvent.StartEvent startEvent -> handleStartEvent(researchId, startEvent);
            case SwarmEvent.EndEvent<?> endEvent -> handleEndEvent(researchId, endEvent);
        }
    }

    private void handleCompletion(UUID researchId) {
        transactionTemplate.executeWithoutResult(_ -> {
            var research = researchRepository.findById(researchId)
                .orElseThrow(() -> new EntityNotFoundException("Research not found: " + researchId));
            research.setStatus(ResearchStatus.COMPLETED);
            researchRepository.save(research);
        });
        eventPublisher.publishComplete(researchId);
    }

    private void handleFailure(UUID researchId, Throwable error) {
        var message = Optional.ofNullable(error.getMessage()).orElse("Research failed");
        log.warn("Research {} failed: {}", researchId, message);
        transactionTemplate.executeWithoutResult(_ -> {
            var research = researchRepository.findById(researchId)
                .orElseThrow(() -> new EntityNotFoundException("Research not found: " + researchId));
            research.setStatus(ResearchStatus.FAILED);
            researchRepository.save(research);
            persistFailedNodes(research, message);
        });
        eventPublisher.publishError(researchId, message);
    }

    private ResearchResponse toResponse(Research research) {
        var nodes = researchNodeRepository.findAllByResearch_Id(research.getId()).stream()
            .map(this::toResponseNode)
            .sorted(Comparator.comparing(ResearchNodeResponse::timestamp, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        return new ResearchResponse(
            research.getId(),
            research.getSwarmId(),
            research.getMetamodel().getSchemaName(),
            research.getStatus(),
            nodes,
            research.getCreatedAt(),
            research.getUpdatedAt()
        );
    }

    private void handleStartEvent(UUID researchId, SwarmEvent.StartEvent event) {
        var descriptor = describeStartEvent(event);

        eventPublisher.publishNodeStart(researchId, descriptor.progressNodeId(), descriptor.nodeType().name());
        eventPublisher.subscribeTokenStream(researchId, descriptor.progressNodeId(), event.tokenStream());

        if (descriptor.persistNode()) {
            transactionTemplate.executeWithoutResult(_ -> savePendingNode(researchId, descriptor));
        }
    }

    private void handleEndEvent(UUID researchId, SwarmEvent.EndEvent<?> event) {
        var descriptor = describeEndEvent(event);

        eventPublisher.publishNodeEnd(researchId, descriptor.progressNodeId(), descriptor.nodeType().name(), event.findings());

        if (descriptor.persistNode()) {
            transactionTemplate.executeWithoutResult(_ -> persistCompletedNode(researchId, descriptor, event));
        }
    }

    private void persistFailedNodes(Research research, String message) {
        var pendingNodes = researchNodeRepository.findAllPendingByResearchId(research.getId());
        for (var pending : pendingNodes) {
            researchNodeRepository.save(
                new FailedResearchNode(
                    research,
                    pending.getNodeType(),
                    pending.getNodeId(),
                    pending.getBranchId(),
                    pending.getStepId(),
                    pending.getPreviousStepId(),
                    pending.getDependencyRefs(),
                    pending.getDependencies(),
                    message
                )
            );
            researchNodeRepository.deleteById(pending.getId());
        }
    }

    private ResearchNodeResponse toResponseNode(ResearchNode node) {
        if (node instanceof CompletedResearchNode completed) {
            return new ResearchNodeResponse.CompletedNodeResponse(
                completed.getId(),
                completed.getNodeType(),
                structuralInfo(completed),
                completed.getDependencies().stream().map(ResearchNode::getId).toList(),
                completed.getPayload(),
                completed.getRawResponse(),
                completed.getCreatedAt()
            );
        }

        if (node instanceof FailedResearchNode failed) {
            return new ResearchNodeResponse.FailedNodeResponse(
                failed.getId(),
                failed.getNodeType(),
                structuralInfo(failed),
                failed.getDependencies().stream().map(ResearchNode::getId).toList(),
                failed.getErrorMessage(),
                failed.getCreatedAt()
            );
        }

        if (node instanceof PendingResearchNode pending) {
            return new ResearchNodeResponse.PendingNodeResponse(
                pending.getNodeType(),
                structuralInfo(pending),
                pending.getDependencies().stream().map(ResearchNode::getId).toList(),
                pending.getCreatedAt()
            );
        }

        throw new IllegalStateException("Unsupported research node type: " + node.getClass().getName());
    }

    private NodeDescriptor describeStartEvent(SwarmEvent.StartEvent event) {
        return switch (event) {
            case SwarmEvent.ScoutStarted scout ->
                new NodeDescriptor(scout.scoutId(), ResearchNodeType.SCOUT, scout.scoutId(), null, null, null, List.of(), true);
            case SwarmEvent.PlanNegotiationStarted plan ->
                new NodeDescriptor(plan.planningId(), ResearchNodeType.PLAN, plan.planningId(), null, null, null, List.of(), true);
            case SwarmEvent.PlanVersionCreationStarted planVersion ->
                new NodeDescriptor(planVersion.planningId() + ":v" + planVersion.versionNumber() + ":create", ResearchNodeType.PLAN,
                    planVersion.planningId(), null, null, null, List.of(), false);
            case SwarmEvent.PlanVersionCritiqueStarted planCritique ->
                new NodeDescriptor(planCritique.planningId() + ":v" + planCritique.versionNumber() + ":critique", ResearchNodeType.PLAN,
                    planCritique.planningId(), null, null, null, List.of(), false);
            case SwarmEvent.BranchExecutionStarted branch ->
                new NodeDescriptor(branch.branchId(), ResearchNodeType.BRANCH, branch.branchId(), branch.branchId(), null, null, List.of(), true);
            case SwarmEvent.StepExecutionStarted step ->
                new NodeDescriptor(step.branchId() + ":" + step.stepId(), ResearchNodeType.STEP,
                    step.branchId() + ":" + step.stepId(), step.branchId(), step.stepId(), step.previousStepId(), step.dependencies(), true);
            case SwarmEvent.AnalysisNegotiationStarted analysis ->
                new NodeDescriptor(analysis.analysisId(), ResearchNodeType.ANALYSIS, analysis.analysisId(), null, null, null, List.of(), true);
            case SwarmEvent.AnalysisVersionCreationStarted analysisVersion ->
                new NodeDescriptor(analysisVersion.analysisId() + ":v" + analysisVersion.versionNumber() + ":create", ResearchNodeType.ANALYSIS,
                    analysisVersion.analysisId(), null, null, null, List.of(), false);
            case SwarmEvent.AnalysisVersionCritiqueStarted analysisCritique ->
                new NodeDescriptor(analysisCritique.analysisId() + ":v" + analysisCritique.versionNumber() + ":critique", ResearchNodeType.ANALYSIS,
                    analysisCritique.analysisId(), null, null, null, List.of(), false);
        };
    }

    private void savePendingNode(UUID researchId, NodeDescriptor descriptor) {
        var research = researchRepository.findById(researchId)
            .orElseThrow(() -> new EntityNotFoundException("Research not found: " + researchId));
        researchNodeRepository.save(new PendingResearchNode(
            research,
            descriptor.progressNodeId(),
            descriptor.nodeType(),
            descriptor.nodeId(),
            descriptor.branchId(),
            descriptor.stepId(),
            descriptor.previousStepId(),
            descriptor.dependencyRefs(),
            java.util.Set.of()
        ));
    }

    private NodeDescriptor describeEndEvent(SwarmEvent.EndEvent<?> event) {
        return switch (event) {
            case SwarmEvent.ScoutFinished scout ->
                new NodeDescriptor(scout.scoutId(), ResearchNodeType.SCOUT, scout.scoutId(), null, null, null, List.of(), true);
            case SwarmEvent.PlanNegotiationFinished plan ->
                new NodeDescriptor(plan.planningId(), ResearchNodeType.PLAN, plan.planningId(), null, null, null, List.of(), true);
            case SwarmEvent.PlanVersionCreationFinished planVersion ->
                new NodeDescriptor(planVersion.planningId() + ":v" + planVersion.versionNumber() + ":create", ResearchNodeType.PLAN,
                    planVersion.planningId(), null, null, null, List.of(), false);
            case SwarmEvent.PlanVersionCritiqueFinished planCritique ->
                new NodeDescriptor(planCritique.planningId() + ":v" + planCritique.versionNumber() + ":critique", ResearchNodeType.PLAN,
                    planCritique.planningId(), null, null, null, List.of(), false);
            case SwarmEvent.BranchExecutionFinished branch ->
                new NodeDescriptor(branch.branchId(), ResearchNodeType.BRANCH, branch.branchId(), branch.branchId(), null, null, List.of(), true);
            case SwarmEvent.StepExecutionFinished step ->
                new NodeDescriptor(step.branchId() + ":" + step.stepId(), ResearchNodeType.STEP,
                    step.branchId() + ":" + step.stepId(), step.branchId(), step.stepId(), step.previousStepId(), step.dependencies(), true);
            case SwarmEvent.AnalysisNegotiationFinished analysis ->
                new NodeDescriptor(analysis.analysisId(), ResearchNodeType.ANALYSIS, analysis.analysisId(), null, null, null, List.of(), true);
            case SwarmEvent.AnalysisVersionCreationFinished analysisVersion ->
                new NodeDescriptor(analysisVersion.analysisId() + ":v" + analysisVersion.versionNumber() + ":create", ResearchNodeType.ANALYSIS,
                    analysisVersion.analysisId(), null, null, null, List.of(), false);
            case SwarmEvent.AnalysisVersionCritiqueFinished analysisCritique ->
                new NodeDescriptor(analysisCritique.analysisId() + ":v" + analysisCritique.versionNumber() + ":critique", ResearchNodeType.ANALYSIS,
                    analysisCritique.analysisId(), null, null, null, List.of(), false);
        };
    }

    private void persistCompletedNode(UUID researchId, NodeDescriptor descriptor, SwarmEvent.EndEvent<?> event) {
        var research = researchRepository.findById(researchId)
            .orElseThrow(() -> new EntityNotFoundException("Research not found: " + researchId));

        var pending = researchNodeRepository.findPendingByResearchIdAndProgressNodeId(researchId, descriptor.progressNodeId())
            .orElse(null);
        if (pending == null) {
            log.debug("Skipping completion persistence for node {} because no pending node exists", descriptor.progressNodeId());
            return;
        }

        var node = new CompletedResearchNode(
            research,
            descriptor.nodeType(),
            pending.getNodeId(),
            pending.getBranchId(),
            pending.getStepId(),
            pending.getPreviousStepId(),
            pending.getDependencyRefs(),
            pending.getDependencies(),
            asObjectPayload(event.findings()),
            Optional.ofNullable(event.rawResponse()).orElse("")
        );
        researchNodeRepository.save(node);
        researchNodeRepository.deleteById(pending.getId());
    }

    private ResearchNodeStructuralInfo structuralInfo(ResearchNode node) {
        return switch (node.getNodeType()) {
            case SCOUT -> new ResearchNodeStructuralInfo.ScoutStructuralInfo(node.getNodeId());
            case PLAN -> new ResearchNodeStructuralInfo.PlanStructuralInfo(node.getNodeId());
            case BRANCH -> new ResearchNodeStructuralInfo.BranchStructuralInfo(node.getNodeId(), node.getBranchId());
            case STEP -> new ResearchNodeStructuralInfo.StepStructuralInfo(
                node.getNodeId(),
                node.getBranchId(),
                node.getStepId(),
                node.getPreviousStepId(),
                node.getDependencyRefs()
            );
            case ANALYSIS -> new ResearchNodeStructuralInfo.AnalysisStructuralInfo(node.getNodeId());
        };
    }

    private ObjectNode asObjectPayload(Object findings) {
        JsonNode node = objectMapper.valueToTree(findings);
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        var wrapper = objectMapper.createObjectNode();
        wrapper.set("value", node);
        return wrapper;
    }

    public ResearchResponse getResearch(UUID id) {
        var research = researchRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Research not found: " + id));
        return toResponse(research);
    }

    public List<ResearchResponse> listResearches() {
        return researchRepository.findAll().stream()
            .sorted(Comparator.comparing(Research::getCreatedAt).reversed())
            .map(this::toResponse)
            .toList();
    }

    public void assertResearchExists(UUID id) {
        if (!researchRepository.existsById(id)) {
            throw new EntityNotFoundException("Research not found: " + id);
        }
    }

    private record NodeDescriptor(
        String progressNodeId,
        ResearchNodeType nodeType,
        String nodeId,
        String branchId,
        String stepId,
        String previousStepId,
        List<StepRef> dependencyRefs,
        boolean persistNode
    ) {}
}
