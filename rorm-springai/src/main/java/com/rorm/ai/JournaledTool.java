package com.rorm.ai;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Journals the tool call result via {@link com.rorm.StepJournal} so it replays from cache
 * on Restate invocation restart instead of re-executing.
 * <p>
 * On a method: journals that method. On a class: journals all {@code @Tool} methods.
 * <p>
 * The journal step name is {@code toolName-toolCallId}, derived from the {@code @Tool} annotation
 * and the tool call ID in the {@link org.springframework.ai.chat.model.ToolContext}.
 * <p>
 * Do NOT use on tools that manage their own journal steps (e.g. awakeable-based ML training tools)
 * — nested {@code ctx.run()} calls break Restate replay.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JournaledTool {
}
