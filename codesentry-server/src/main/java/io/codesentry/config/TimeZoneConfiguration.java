package io.codesentry.config;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeZoneConfiguration {
  public static final String ZONE_ID = "Asia/Shanghai";

  @PostConstruct
  public void configureDefaultTimeZone() {
    TimeZone.setDefault(TimeZone.getTimeZone(ZONE_ID));
  }
}
