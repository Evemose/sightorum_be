package com.rorm.ml.restate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.JobSpec;
import com.rorm.StepJournal;
import dev.restate.sdk.ObjectContext;
import dev.restate.sdk.annotation.Exclusive;
import dev.restate.sdk.annotation.Name;
import dev.restate.sdk.common.AbortedExecutionException;
import dev.restate.sdk.springboot.RestateVirtualObject;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;

@RestateVirtualObject(configuration = "durableJobConfig")
@Name("DurableJobService")
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "true")
@RequiredArgsConstructor
public class DurableJobService {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    @Exclusive
    public Object execute(ObjectContext ctx, JobSpec spec) throws Throwable {
        var journal = new RestateStepJournal(ctx);
        try {
            return ScopedValue.where(StepJournal.CURRENT, journal).call(() -> {
                var bean = applicationContext.getBean(spec.beanName());
                var paramTypes = resolveTypes(spec.argTypes());
                var meth = ReflectionUtils.findMethod(bean.getClass(), spec.methodName(), paramTypes);
                if (meth == null) {
                    throw new IllegalStateException("Method not found: " + spec.methodName());
                }
                meth.setAccessible(true);
                var convertedArgs = convertArgs(spec.args(), paramTypes);
                try {
                    return meth.invoke(bean, convertedArgs);
                } catch (InvocationTargetException e) {
                    if (e.getCause() != null) {
                        throw e.getCause();
                    }
                    throw e;
                }
            });
        } catch (Throwable e) {
            var localE = e;
            while (localE.getCause() != null) {
                if (localE.getCause() instanceof AbortedExecutionException) {
                    throw localE.getCause();
                }
                localE = localE.getCause();
            }
            throw localE;
        }
    }

    private Class<?>[] resolveTypes(String[] typeNames) throws ClassNotFoundException {
        var types = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            types[i] = Class.forName(typeNames[i]);
        }
        return types;
    }

    private Object[] convertArgs(Object[] args, Class<?>[] targetTypes) {
        var converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null || targetTypes[i].isInstance(args[i])) {
                converted[i] = args[i];
            } else {
                converted[i] = objectMapper.convertValue(args[i], targetTypes[i]);
            }
        }
        return converted;
    }
}
