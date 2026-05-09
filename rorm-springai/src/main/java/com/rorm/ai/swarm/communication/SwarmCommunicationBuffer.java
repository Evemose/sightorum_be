package com.rorm.ai.swarm.communication;

import java.util.List;

/**
 * Per-run question-and-answer mailbox. Reads the agent registry from
 * {@link SwarmContext} and polls the addressed peers by issuing a
 * chat call against their own {@code chatId} with the question text
 * wrapped in a new user message. The asker is always excluded; cycles
 * in the in-flight ask graph are refused at the context boundary so
 * mutual blocks are impossible.
 */
public interface SwarmCommunicationBuffer {

    /**
     * Polls every registered agent of {@code question.role()}
     * (asker excluded). Returns one {@link Answer} per peer that
     * responded.
     */
    List<Answer> ask(String runId, String askerChatId, Question question);

    /**
     * Walks the asker's upstream chain breadth-first through parent
     * event tokens and polls the first ancestor whose role matches
     * {@code role}. Returns a single-element list with that ancestor's
     * {@link Answer}, or an empty list when no upstream agent of that
     * role exists. Asker excluded; ties at the same depth are broken
     * by registration order.
     */
    List<Answer> askUpstream(String runId, String askerChatId, String role, String questionText);
}
