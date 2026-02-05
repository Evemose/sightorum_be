package com.rorm.ai.chat.node;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

@Entity
@Getter
@ToString
@RequiredArgsConstructor
@Table(name = "training_progress_nodes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
public class TrainingProgressNode extends ChatNode {

    @NonNull
    @NotNull
    private UUID trainingId;

    @Setter
    @NonNull
    @NotNull
    private Double progressPercentage;
}
