package io.codesentry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    @Bean("scanTaskExecutor")
    public TaskExecutor scanTaskExecutor(CodeSentryProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getScanning().getWorkerCount());
        executor.setMaxPoolSize(properties.getScanning().getWorkerCount());
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("codesentry-scan-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean("aiReviewTaskExecutor")
    public TaskExecutor aiReviewTaskExecutor(CodeSentryProperties properties) {
        int workers = Math.max(1, Math.min(properties.getAi().getMaxConcurrency(), 8));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("codesentry-ai-review-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
