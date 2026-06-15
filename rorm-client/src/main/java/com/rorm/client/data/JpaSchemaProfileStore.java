package com.rorm.client.data;

import com.rorm.client.metamodel.MetamodelRepository;
import com.rorm.dataimport.pipeline.profile.SchemaProfile;
import com.rorm.dataimport.pipeline.profile.SchemaProfileStore;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class JpaSchemaProfileStore implements SchemaProfileStore {

    private final SchemaAnalysisProfileRepository repository;
    private final MetamodelRepository metamodelRepository;

    /**
     * Bulk lookup used by list endpoints to avoid one round-trip per
     * schema. Missing schemas are absent from the returned map.
     */
    @Transactional(readOnly = true)
    public Map<String, SchemaProfile> getAll(Collection<String> schemaNames) {
        if (schemaNames.isEmpty()) return Map.of();
        return repository.findByMetamodel_SchemaNameIn(schemaNames).stream()
            .collect(Collectors.toUnmodifiableMap(
                SchemaAnalysisProfile::getSchemaName,
                SchemaAnalysisProfile::getProfile));
    }

    @Override
    @Transactional
    public void store(String schema, SchemaProfile profile) {
        var existing = repository.findByMetamodel_SchemaName(schema);
        if (existing.isPresent()) {
            existing.get().setProfile(profile);
        } else {
            var metamodel = metamodelRepository.findBySchemaName(schema)
                .orElseThrow(() -> new EntityNotFoundException(
                    "Cannot store schema profile: no metamodel registered for schema '" + schema + "'"));
            repository.save(new SchemaAnalysisProfile(metamodel, profile));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SchemaProfile> get(String schema) {
        return repository.findByMetamodel_SchemaName(schema)
            .map(SchemaAnalysisProfile::getProfile);
    }

    @Override
    @Transactional
    public void remove(String schema) {
        repository.deleteByMetamodel_SchemaName(schema);
    }
}
