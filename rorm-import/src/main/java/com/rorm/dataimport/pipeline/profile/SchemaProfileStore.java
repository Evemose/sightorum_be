package com.rorm.dataimport.pipeline.profile;

import java.util.Optional;

public interface SchemaProfileStore {

    void store(String schema, SchemaProfile profile);

    Optional<SchemaProfile> get(String schema);

    void remove(String schema);
}
