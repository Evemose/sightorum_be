package com.rorm.ml.peristence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface StabilitySelectionRepository extends JpaRepository<StabilitySelectionRun, UUID> {
    Optional<StabilitySelectionRun> findByAnalysisId(UUID analysisId);
}
