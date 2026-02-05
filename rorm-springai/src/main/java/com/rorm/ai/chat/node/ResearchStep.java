package com.rorm.ai.chat.node;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record ResearchStep(
    @Column(columnDefinition = "text") String reasoning,
    @Column(columnDefinition = "text") String action,
    @Column(columnDefinition = "text") String observation
) {
}
