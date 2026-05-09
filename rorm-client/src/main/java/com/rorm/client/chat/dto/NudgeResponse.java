package com.rorm.client.chat.dto;

/**
 * Response for a nudge/proceed call against a research run. {@code resumed}
 * is true when a paused or suspended top-level invocation was found and a
 * resume was issued; false when no paused invocation matched the runId
 * (the run may already be progressing cleanly, or may not exist).
 */
public record NudgeResponse(String runId, boolean resumed) {}
