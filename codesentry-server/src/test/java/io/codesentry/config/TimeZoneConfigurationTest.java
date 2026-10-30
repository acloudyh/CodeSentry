package io.codesentry.config;

import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeZoneConfigurationTest {
    @Test
    void configuresAsiaShanghaiAsJvmDefault() {
        TimeZone original = TimeZone.getDefault();
        try {
            new TimeZoneConfiguration().configureDefaultTimeZone();
            assertEquals(TimeZoneConfiguration.ZONE_ID, TimeZone.getDefault().getID());
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
