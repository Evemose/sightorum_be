package com.rorm.ml.runtime;

import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.StepJournal;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.ApplicationContext;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

@RequiredArgsConstructor
public class InMemoryDurableRuntime implements DurableRuntime {

    private final ApplicationContext applicationContext;

    @SneakyThrows
    @Override
    public Object submit(String sessionId, JobSpec spec) {
        return ScopedValue.where(StepJournal.CURRENT, StepJournal.NOOP).call(() -> {
            var bean = applicationContext.getBean(spec.beanName());
            var paramTypes = Arrays.stream(spec.args())
                .map(arg -> arg != null ? arg.getClass() : Object.class)
                .toArray(Class[]::new);
            var handle = MethodHandles.lookup()
                .findVirtual(bean.getClass(), spec.methodName(), MethodType.methodType(Object.class, paramTypes));
            return handle.invoke(bean, spec.args());
        });
    }
}
