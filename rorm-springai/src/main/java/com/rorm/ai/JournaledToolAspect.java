package com.rorm.ai;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class JournaledToolAspect {

    @Pointcut("@annotation(com.rorm.ai.JournaledTool)")
    void methodAnnotated() {
    }

    @Pointcut("@within(com.rorm.ai.JournaledTool) && @annotation(org.springframework.ai.tool.annotation.Tool)")
    void classAnnotated() {
    }

    @Around("methodAnnotated() || classAnnotated()")
    @SuppressWarnings("unchecked")
    public Object journalToolCall(ProceedingJoinPoint pjp) throws Throwable {
        var toolContext = findToolContext(pjp.getArgs());
        if (toolContext == null) {
            log.warn("@JournalledTool on {} but no ToolContext parameter — executing without journal",
                pjp.getSignature().toShortString());
            return pjp.proceed();
        }

        var ctx = RormToolContext.from(toolContext);
        var journal = ctx.stepJournal();
        if (journal == null) {
            return pjp.proceed();
        }

        var method = ((MethodSignature) pjp.getSignature()).getMethod();
        var tool = method.getAnnotation(Tool.class);
        var toolName = (tool != null && !tool.name().isEmpty()) ? tool.name() : method.getName();
        var callId = ctx.id();
        var stepName = "tool-" + toolName + "-" + (callId != null ? callId : "unknown");

        return journal.run(stepName, (Class<Object>) method.getReturnType(), () -> {
            try {
                return pjp.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
    }

    private ToolContext findToolContext(Object[] args) {
        for (var arg : args) {
            if (arg instanceof ToolContext tc) {
                return tc;
            }
        }
        return null;
    }
}
