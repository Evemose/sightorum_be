package com.rorm.ai.chat;

import com.rorm.ai.chat.node.ChatNode;
import com.rorm.ai.chat.node.MessageLike;
import com.rorm.ai.chat.node.TrainingQueuedNode;
import com.rorm.metamodel.ModelSpace;
import io.hypersistence.utils.hibernate.type.json.JsonType;
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
import java.util.*;
import java.util.stream.Stream;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatProgressStatus status;

    @OrderBy("createdAt ASC")
    @OneToMany(orphanRemoval = true, cascade = CascadeType.ALL)
    private final List<ChatNode> pastNodes = new ArrayList<>();

    @OrderBy("createdAt ASC")
    @OneToMany(orphanRemoval = true, cascade = CascadeType.ALL)
    private final List<ChatNode> memoryNodes = new ArrayList<>();

    @Type(JsonType.class)
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

    @SuppressWarnings("DataFlowIssue")
    private Stream<MessageLike> streamMessages() {
        return Stream.concat(
            Stream.concat(
                Stream.ofNullable(parent).flatMap(ChatProgress::streamMessages),
                pastNodes.stream().filter(MessageLike.class::isInstance).map(MessageLike.class::cast)
            ),
            memoryNodes.stream().filter(MessageLike.class::isInstance).map(MessageLike.class::cast)
        );
    }

    public List<MessageLike> getMessages() {
        return streamMessages()
            .sorted(Comparator.comparing(MessageLike::getCreatedAt))
            .toList();
    }

    public void setMemoryNodes(List<ChatNode> nodes) {
        this.memoryNodes.clear();
        this.memoryNodes.addAll(nodes);
        this.pastNodes.removeAll(nodes);
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
        this.memoryNodes.add(node);
    }

    public UUID getConversationId() {
        return id;
    }

    public ChatProgress fork(TrainingQueuedNode firstNode) {
        var child = new ChatProgress(modelSpace);
        child.parent = this;
        child.pastNodes.add(firstNode);
        return child;
    }

    public List<MessageLike> getMemoryMessages() {
        return memoryNodes.stream()
            .filter(MessageLike.class::isInstance)
            .map(MessageLike.class::cast)
            .toList();
    }
}
