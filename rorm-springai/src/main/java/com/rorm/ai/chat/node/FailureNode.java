package com.rorm.ai.chat.node;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.Type;

@Entity
@Getter
@ToString
@Table(name = "failure_nodes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(callSuper = false)
public non-sealed class FailureNode extends ChatNode implements MessageLike {

    @NonNull
    @NotBlank
    private String message;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private ObjectNode detail;

    public FailureNode(@NonNull String message, ObjectNode detail) {
        this.message = message;
        this.detail = detail;
    }

    @Override
    public String getTitle() {
        return message.length() <= 20 ? message : message.substring(0, 20) + "...";
    }

    @Override
    public String getContent() {
        return """
            Error: ${message}
            Detail: ${detail}
            """
            .replace("${message}", message)
            .replace("${detail}", detail.toString());
    }

    @Override
    public Sender getSender() {
        return Sender.SYSTEM;
    }
}
