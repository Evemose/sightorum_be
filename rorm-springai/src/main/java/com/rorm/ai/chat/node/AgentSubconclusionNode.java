package com.rorm.ai.chat.node;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.springframework.ai.chat.memory.ChatMemoryRepository;

import java.util.List;

@Entity
@Getter
@ToString
@RequiredArgsConstructor
@Table(name = "agent_subconclusion_nodes")
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentSubconclusionNode extends ChatNode {

    @NonNull
    @NotNull
    @Column(columnDefinition = "text")
    private String summary;

    @NonNull
    @NotNull
    @Column(columnDefinition = "text")
    private String keyInsight;

    @NonNull
    @NotNull
    @Column(columnDefinition = "text")
    private String details;

    @NonNull
    @NotNull
    @ElementCollection
    private List<ResearchStep> researchSteps;

    /// A conversation id that can be passed to [ChatMemoryRepository#findByConversationId]
    /// to retrieve the relevant chat memory for this subconclusion.
    @NotNull
    @NonNull
    @Column(unique = true)
    private String conversationId;

}
