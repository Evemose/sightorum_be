package com.rorm;

/**
 * Serializable descriptor for a job invocation.
 * The runtime resolves the bean and calls the method with these args.
 * In Restate mode, this is what gets sent to the handler and journaled.
 *
 * @param beanName   Spring bean name of the target component
 * @param methodName method to invoke on the bean
 * @param args       serializable arguments
 */
public record JobSpec(
    String beanName,
    String methodName,
    Object[] args
) {

    public JobSpec(String beanName, String methodName) {
        this(beanName, methodName, new Object[0]);
    }

}
