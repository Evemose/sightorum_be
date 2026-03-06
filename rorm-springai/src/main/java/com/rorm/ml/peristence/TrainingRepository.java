package com.rorm.ml.peristence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface TrainingRepository extends JpaRepository<Training, UUID> {
    Optional<Training> findByTrainingId(UUID trainingId);
}
