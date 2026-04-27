package com.rorm.ml.runtime;

import com.rorm.AwakableHandle;
import com.rorm.CompletableDurableFuture;
import com.rorm.DurableFuture;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.StepJournal;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
public class InMemoryDurableRuntime implements DurableRuntime {

    private final ApplicationContext applicationContext;

    @SneakyThrows
    @Override
    public Object submit(String sessionId, JobSpec spec) {
        return ScopedValue.where(StepJournal.CURRENT, StepJournal.DEFAULT).call(() -> {
            var bean = applicationContext.getBean(spec.beanName());
            var paramTypes = resolveTypes(spec.argTypes());
            var meth = ReflectionUtils.findMethod(bean.getClass(), spec.methodName(), paramTypes);
            if (meth == null) {
                throw new IllegalStateException(
                    "Method not found: " + bean.getClass().getName() + "#" + spec.methodName());
            }
            meth.setAccessible(true);
            try {
                return meth.invoke(bean, spec.args());
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        });
    }

    private static Class<?>[] resolveTypes(String[] typeNames) throws ClassNotFoundException {
        if (typeNames == null || typeNames.length == 0) {
            return new Class<?>[0];
        }
        var types = new Class<?>[typeNames.length];
        for (var i = 0; i < typeNames.length; i++) {
            types[i] = Class.forName(typeNames[i]);
        }
        return types;
    }

    @Override
    public DurableFuture<Object> submitAsync(String sessionId, JobSpec spec) {
        return CompletableDurableFuture.by(CompletableFuture.supplyAsync(() -> submit(sessionId, spec)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> AwakableHandle<T> handle(String awakableId, Class<T> resultType) {
        return (AwakableHandle<T>) StepJournal.DEFAULT.getAwakableHandle(awakableId);
    }
}
