package com.rorm.client.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SchemaAnalysisProfileRepository extends JpaRepository<SchemaAnalysisProfile, UUID> {

    Optional<SchemaAnalysisProfile> findBySchemaName(String schemaName);

    void deleteBySchemaName(String schemaName);
}
