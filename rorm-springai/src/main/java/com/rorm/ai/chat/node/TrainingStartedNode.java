package com.rorm.ai.chat.node;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.Type;

import java.util.UUID;

@Entity
@Getter
@ToString
@RequiredArgsConstructor
@Table(name = "training_started_nodes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
public class TrainingStartedNode extends ChatNode {

    @NonNull
    @NotNull
    private UUID trainingId;

    @NonNull
    @NotNull
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private ObjectNode payload;
}
