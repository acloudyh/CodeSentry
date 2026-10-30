package io.codesentry.config;

import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.TimeZone;

@Configuration
public class TimeZoneConfiguration {
    public static final String ZONE_ID = "Asia/Shanghai";

    @PostConstruct
    public void configureDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE_ID));
    }
}
