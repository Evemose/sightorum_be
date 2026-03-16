package com.rorm.ml.jobs;

import com.rorm.durable.JobSpec;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.ApplicationContext;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

/**
 * Resolves a submitter bean by name and invokes its entry method via MethodHandle.
 * Used by both in-memory and Restate runtimes.
 */
@RequiredArgsConstructor
public class JobExecutor {

    private final ApplicationContext applicationContext;

    @SneakyThrows
    public Object execute(JobSpec spec) {
        var bean = applicationContext.getBean(spec.beanName());
        var paramTypes = Arrays.stream(spec.args())
            .map(arg -> arg != null ? arg.getClass() : Object.class)
            .toArray(Class[]::new);
        var handle = MethodHandles.lookup()
            .findVirtual(bean.getClass(), spec.methodName(), MethodType.methodType(Object.class, paramTypes));
        return handle.invoke(bean, spec.args());
    }
}
