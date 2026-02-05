package com.rorm.ai.chat.node;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Entity
@Getter
@ToString
@RequiredArgsConstructor
@Table(name = "chat_forked_nodes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
public class ChatForkedNode extends ChatNode {

    @NonNull
    @NotNull
    @ManyToOne(optional = false)
    private ChatNode forkPoint;

    @NonNull
    @NotBlank
    @Column(columnDefinition = "text")
    private String reason;

    @NonNull
    @NotBlank
    @Column(columnDefinition = "text")
    private String furtherInstructions;


}
