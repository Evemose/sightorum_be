package com.rorm.ai.chat.node;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ChatNodeRepository extends JpaRepository<ChatNode, UUID> {

    @Query("from TrainingProgressNode tr where tr.trainingId = :trainingId")
    Optional<TrainingProgressNode> findTrainingProgressByTrainingId(UUID trainingId);

}
