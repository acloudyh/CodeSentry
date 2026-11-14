package io.codesentry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

  /** 报告打包独立串行执行，限制字体嵌入及 PDF 渲染的同时内存占用。 */
  @Bean("reportPackageTaskExecutor")
  public TaskExecutor reportPackageTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("codesentry-report-zip-");
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.setAwaitTerminationSeconds(5);
    executor.initialize();
    return executor;
  }

  @Bean("remediationTaskExecutor")
  public TaskExecutor remediationTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("codesentry-remediation-");
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.setAwaitTerminationSeconds(5);
    executor.initialize();
    return executor;
  }

  @Bean("repositoryTaskExecutor")
  public TaskExecutor repositoryTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("codesentry-git-");
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.setAwaitTerminationSeconds(5);
    executor.initialize();
    return executor;
  }

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
    int workers = 2;
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

  /** Shared worker budget across every batch and individual review. */
  @Bean("aiReviewWorkerExecutor")
  public TaskExecutor aiReviewWorkerExecutor(CodeSentryProperties properties) {
    int workers = Math.max(1, Math.min(properties.getAi().getMaxConcurrency(), 8));
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(workers);
    executor.setMaxPoolSize(workers);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("codesentry-ai-unit-");
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.setAwaitTerminationSeconds(10);
    executor.initialize();
    return executor;
  }
}
