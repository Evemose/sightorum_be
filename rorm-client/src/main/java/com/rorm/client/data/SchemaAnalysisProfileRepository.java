package com.rorm.client.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SchemaAnalysisProfileRepository extends JpaRepository<SchemaAnalysisProfile, UUID> {

    Optional<SchemaAnalysisProfile> findByMetamodel_SchemaName(String schemaName);

    List<SchemaAnalysisProfile> findByMetamodel_SchemaNameIn(Collection<String> schemaNames);

    void deleteByMetamodel_SchemaName(String schemaName);
}
