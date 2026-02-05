package com.rorm.ai.chat;

import com.rorm.ai.chat.node.ChatForkedNode;
import com.rorm.ai.chat.node.ChatNode;
import com.rorm.metamodel.ModelSpace;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Getter
@Entity
@Table(name = "chat_progress")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatProgress {

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OrderBy("createdAt ASC")
    @OneToMany(orphanRemoval = true, cascade = CascadeType.ALL)
    private final List<ChatNode> nodes = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatProgressStatus status;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private ModelSpace modelSpace;

    @Nullable
    @ManyToOne
    private ChatProgress parent;

    @CreatedDate
    private Instant createdAt;

    public ChatProgress(ModelSpace modelSpace) {
        this.status = ChatProgressStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.modelSpace = modelSpace;
    }

    @SuppressWarnings("NullableProblems")
    public Optional<ChatProgress> getParent() {
        return Optional.ofNullable(parent);
    }

    public void fail() {
        this.status = ChatProgressStatus.FAILED;
    }

    public void complete() {
        this.status = ChatProgressStatus.COMPLETED;
    }

    public void waitForTraining() {
        this.status = ChatProgressStatus.WAITING_FOR_TRAINING;
    }

    public void addNode(ChatNode node) {
        this.nodes.add(node);
    }

    /**
     * Removes all nodes starting from (and including) the node with the given ID.
     * Used for corrections where we need to rewind the conversation.
     *
     * @param nodeId the ID of the first node to remove
     * @return true if nodes were removed, false if the node was not found
     */
    public boolean truncateNodesFrom(UUID nodeId) {
        var index = -1;
        for (var i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getId().equals(nodeId)) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            return false;
        }
        nodes.subList(index, nodes.size()).clear();
        return true;
    }

    public UUID getConversationId() {
        return id;
    }

    public ChatProgress fork(String reason, String furtherInstructions) {
        if (this.status != ChatProgressStatus.ACTIVE) {
            throw new IllegalStateException("Can only fork an active chat progress");
        }
        if (this.nodes.isEmpty()) {
            throw new IllegalStateException("Cannot fork a chat progress with no past nodes");
        }
        var child = new ChatProgress(modelSpace);
        child.parent = this;
        child.nodes.add(new ChatForkedNode(this.nodes.getLast(), reason, furtherInstructions));
        return child;
    }


}
