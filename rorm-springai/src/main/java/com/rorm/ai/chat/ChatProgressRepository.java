package com.rorm.ai.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatProgressRepository extends JpaRepository<ChatProgress, UUID> {

    @Query("""
        FROM ChatProgress cp
        JOIN cp.nodes n
        WHERE TYPE(n) = TrainingQueuedNode
        AND TREAT(n AS TrainingQueuedNode).trainingId = :trainingId
        """)
    Optional<ChatProgress> findByTrainingId(UUID trainingId);

    List<ChatProgress> findByParent_Id(UUID parentId);

}
