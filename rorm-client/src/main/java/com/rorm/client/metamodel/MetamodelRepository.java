package com.rorm.client.metamodel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetamodelRepository extends JpaRepository<Metamodel, UUID> {

    Optional<Metamodel> findBySchemaName(String schemaName);

    boolean existsBySchemaName(String schemaName);
}
