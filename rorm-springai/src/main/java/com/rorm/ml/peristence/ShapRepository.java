package com.rorm.ml.peristence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ShapRepository extends JpaRepository<ShapRun, UUID> {
    Optional<ShapRun> findByAnalysisId(UUID analysisId);
}
