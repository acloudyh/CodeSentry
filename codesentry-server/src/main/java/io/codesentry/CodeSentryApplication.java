package io.codesentry;

import io.codesentry.config.CodeSentryProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@org.springframework.scheduling.annotation.EnableScheduling
@EnableAsync
@MapperScan({
  "io.codesentry.dashboard.mapper",
  "io.codesentry.project.mapper",
  "io.codesentry.scan.mapper",
  "io.codesentry.finding.mapper",
  "io.codesentry.ai.mapper",
  "io.codesentry.report.mapper"
})
@EnableConfigurationProperties(CodeSentryProperties.class)
@SpringBootApplication
public class CodeSentryApplication {

  public static void main(String[] args) {
    SpringApplication.run(CodeSentryApplication.class, args);
  }
}
