package com.rorm.ai.chat.node;

import java.time.Instant;
import java.util.UUID;

public sealed interface MessageLike permits MessageNode, ToolCallNode, FailureNode {

    UUID getId();

    String getTitle();

    String getContent();

    Sender getSender();

    Instant getCreatedAt();

}
