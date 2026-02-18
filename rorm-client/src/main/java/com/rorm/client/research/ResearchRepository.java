package com.rorm.client.research;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResearchRepository extends JpaRepository<Research, UUID> {

    Optional<Research> findBySwarmId(String swarmId);

    List<Research> findByMetamodel_Id(UUID metamodelId);
}
