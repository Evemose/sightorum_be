package com.rorm;

import java.util.Arrays;

/**
 * Serializable descriptor for a job invocation.
 * The runtime resolves the bean and calls the method with these args.
 * In Restate mode, this is what gets sent to the handler and journaled.
 *
 * @param beanName   Spring bean name of the target component
 * @param methodName method to invoke on the bean
 * @param args       serializable arguments
 * @param argTypes   fully qualified class names of the original argument types
 */
public record JobSpec(
    String beanName,
    String methodName,
    Object[] args,
    String[] argTypes
) {

    public JobSpec(String beanName, String methodName) {
        this(beanName, methodName, new Object[0], new String[0]);
    }

    public JobSpec(String beanName, String methodName, Object[] args) {
        this(beanName, methodName, args,
            Arrays.stream(args)
                .map(a -> a != null ? a.getClass().getName() : "java.lang.Object")
                .toArray(String[]::new));
    }

}
