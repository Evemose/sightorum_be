package com.rorm.durable;

/**
 * Serializable spec describing a job invocation.
 * Journaled by Restate — on replay, the submitter bean is re-resolved
 * from the application context by name and the entry method is re-invoked.
 *
 * @param beanName   Spring bean name of the generated submitter
 * @param methodName entry point method name on the job class
 * @param args       serializable arguments for the entry method
 */
public record JobSpec(
    String beanName,
    String methodName,
    Object[] args
) {
}
