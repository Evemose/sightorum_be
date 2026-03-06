package com.rorm.client.data;

import com.rorm.dataimport.pipeline.profile.SchemaProfile;
import com.rorm.dataimport.pipeline.profile.SchemaProfileStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaSchemaProfileStore implements SchemaProfileStore {

    private final SchemaAnalysisProfileRepository repository;

    @Override
    @Transactional
    public void store(String schema, SchemaProfile profile) {
        var existing = repository.findBySchemaName(schema);
        if (existing.isPresent()) {
            existing.get().setProfile(profile);
        } else {
            repository.save(new SchemaAnalysisProfile(schema, profile));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SchemaProfile> get(String schema) {
        return repository.findBySchemaName(schema)
            .map(SchemaAnalysisProfile::getProfile);
    }

    @Override
    @Transactional
    public void remove(String schema) {
        repository.deleteBySchemaName(schema);
    }
}
