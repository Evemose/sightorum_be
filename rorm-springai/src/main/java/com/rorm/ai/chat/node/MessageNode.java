package com.rorm.ai.chat.node;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Entity
@Getter
@ToString
@RequiredArgsConstructor
@Table(name = "message_nodes")
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public non-sealed class MessageNode extends ChatNode implements MessageLike {

    @NonNull
    @NotBlank
    @Column(columnDefinition = "text")
    private String content;

    @NonNull
    @NotNull
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Sender sender;

    public static MessageNode user(String content) {
        return new MessageNode(content, Sender.USER);
    }

    public static MessageNode assistant(String content) {
        return new MessageNode(content, Sender.ASSISTANT);
    }

    public static MessageNode system(String content) {
        return new MessageNode(content, Sender.SYSTEM);
    }

    @Override
    public String getTitle() {
        return content.length() <= 20 ? content : content.substring(0, 20) + "...";
    }

}
