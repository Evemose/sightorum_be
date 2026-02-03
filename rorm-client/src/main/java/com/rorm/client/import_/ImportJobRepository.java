package com.rorm.client.import_;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

    List<ImportJob> findByStatusOrderByStartedAtDesc(ImportJobStatus status);

    List<ImportJob> findByTargetSchemaOrderByStartedAtDesc(String targetSchema);
}
