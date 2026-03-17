package com.rorm.ml.restate;

import com.rorm.durable.JobSpec;
import com.rorm.durable.StepJournal;
import dev.restate.sdk.ObjectContext;
import dev.restate.sdk.annotation.Exclusive;
import dev.restate.sdk.annotation.Name;
import dev.restate.sdk.springboot.RestateVirtualObject;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;

import java.util.Arrays;

@RestateVirtualObject
@Name("DurableJobService")
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "true")
@RequiredArgsConstructor
public class DurableJobService {

    private final ApplicationContext applicationContext;

    @SneakyThrows
    @Exclusive
    public Object execute(ObjectContext ctx, JobSpec spec) {
        var journal = new RestateStepJournal(ctx);
        return ScopedValue.where(StepJournal.CURRENT, journal).call(() -> {
            var bean = applicationContext.getBean(spec.beanName());
            var paramTypes = Arrays.stream(spec.args())
                .map(arg -> arg != null ? arg.getClass() : Object.class)
                .toArray(Class[]::new);
            var meth = ReflectionUtils.findMethod(bean.getClass(), spec.methodName(), paramTypes);
            if (meth == null) {
                throw new IllegalStateException("Method not found: " + spec.methodName());
            }
            meth.setAccessible(true);
            return meth.invoke(bean, spec.args());
        });
    }
}
