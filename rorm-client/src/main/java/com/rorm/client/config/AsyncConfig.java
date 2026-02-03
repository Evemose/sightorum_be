package com.rorm.client.config;

import com.rorm.dataimport.pipeline.RormImportAutoConfiguration.ImportTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig implements AsyncConfigurer, WebMvcConfigurer {

    @ImportTaskExecutor
    @Bean(defaultCandidate = false)
    public AsyncTaskExecutor importTaskExecutor() {
        var executor = new SimpleAsyncTaskExecutor();
        executor.setThreadNamePrefix("import-");
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return defaultTaskExecutor();
    }

    @Bean
    @Primary
    public Executor defaultTaskExecutor() {
        var executor = new SimpleAsyncTaskExecutor();
        executor.setThreadNamePrefix("default-");
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(sseTaskExecutor());
        configurer.setDefaultTimeout(3600000L); // 1 hour for SSE
    }

    @Bean(defaultCandidate = false)
    public AsyncTaskExecutor sseTaskExecutor() {
        var executor = new SimpleAsyncTaskExecutor();
        executor.setThreadNamePrefix("sse-");
        return executor;
    }
}
