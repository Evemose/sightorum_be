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
        JOIN cp.pastNodes n
        WHERE TYPE(n) = TrainingQueuedNode
        AND TREAT(n AS TrainingQueuedNode).trainingId = :trainingId
        """)
    Optional<ChatProgress> findByTrainingId(UUID trainingId);

    @Query("SELECT CAST(cp.id AS string) FROM ChatProgress cp")
    List<String> findAllIds();
}
