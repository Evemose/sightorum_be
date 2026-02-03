package com.rorm.ai.chat.node;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.Type;

import java.util.UUID;

@Entity
@Getter
@ToString
@Table(name = "tool_call_nodes")
@RequiredArgsConstructor
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public non-sealed class ToolCallNode extends ChatNode implements MessageLike {

    @NonNull
    @NotBlank
    @Column(columnDefinition = "text")
    private String description;
    @NonNull
    @NotNull
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private ObjectNode response;

    public ToolCallNode(UUID id, String description, ObjectNode response) {
        this.id = id;
        this.description = description;
        this.response = response;
    }

    @Override
    public String getTitle() {
        return "Tool Call: " + description;
    }

    @Override
    public String getContent() {
        return """
            Request:
            ${description}
            Response:
            ${response}
            """.replace("${description}", description)
            .replace("${response}", response.toPrettyString());
    }

    @Override
    public Sender getSender() {
        return Sender.TOOL_CALL;
    }
}
