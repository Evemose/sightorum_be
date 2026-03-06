package com.rorm.ml.peristence;

import com.rorm.ml.dto.model.TrainingModelSpec;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.Type;

import java.util.List;

@Embeddable
public record PersistentMLRequest(
    String reason,
    String furtherInstructions,
    String sql,
    String targetColumn,
    @ElementCollection List<String> featureColumns,
    @Type(JsonType.class) @Column(columnDefinition = "jsonb") TrainingModelSpec modelSpec
) {}
