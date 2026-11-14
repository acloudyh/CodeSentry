package io.codesentry.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.TimeZone;
import org.junit.jupiter.api.Test;

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
