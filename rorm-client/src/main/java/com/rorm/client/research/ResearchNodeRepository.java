package com.rorm.client.research;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResearchNodeRepository extends JpaRepository<ResearchNode, UUID> {

    List<ResearchNode> findAllByResearch_Id(UUID researchId);

    @Query("select p from PendingResearchNode p where p.research.id = :researchId and p.progressNodeId = :progressNodeId")
    Optional<PendingResearchNode> findPendingByResearchIdAndProgressNodeId(UUID researchId, String progressNodeId);

    @Query("select p from PendingResearchNode p where p.research.id = :researchId")
    List<PendingResearchNode> findAllPendingByResearchId(UUID researchId);
}
