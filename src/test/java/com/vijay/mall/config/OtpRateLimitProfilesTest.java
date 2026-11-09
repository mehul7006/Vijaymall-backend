package com.vijay.mall.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The hourly login-code limits: strict in production, very high in the dev profile, and overridable by environment variables in both. */
class OtpRateLimitProfilesTest {
  private static StandardEnvironment env(String profileFile, Map<String, Object> vars) throws Exception {
    var e = new StandardEnvironment();
    e.getPropertySources().addFirst(new MapPropertySource("env", vars));
    if (profileFile != null) {
      for (var ps : new YamlPropertySourceLoader().load("profile", new ClassPathResource(profileFile))) e.getPropertySources().addLast(ps);
    }
    for (var ps : new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yml"))) e.getPropertySources().addLast(ps);
    return e;
  }

  @Test
  void productionKeepsFivePerEmailAndTwentyPerAddress() throws Exception {
    var e = env("application-prod.yml", Map.of());
    assertThat(e.getProperty("app.rate-limit.otp-per-email-hour", Integer.class)).isEqualTo(5);
    assertThat(e.getProperty("app.rate-limit.otp-per-ip-hour", Integer.class)).isEqualTo(20);
  }

  @Test
  void devRaisesBothLimitsVeryHigh() throws Exception {
    var e = env("application-dev.yml", Map.of());
    assertThat(e.getProperty("app.rate-limit.otp-per-email-hour", Integer.class)).isEqualTo(1000);
    assertThat(e.getProperty("app.rate-limit.otp-per-ip-hour", Integer.class)).isEqualTo(10000);
  }

  @Test
  void environmentVariablesOverrideTheLimits() throws Exception {
    var vars = Map.<String, Object>of("OTP_PER_EMAIL_HOUR", "7", "OTP_PER_IP_HOUR", "9");
    for (String profile : new String[] {"application-dev.yml", "application-prod.yml"}) {
      var e = env(profile, vars);
      assertThat(e.getProperty("app.rate-limit.otp-per-email-hour", Integer.class)).isEqualTo(7);
      assertThat(e.getProperty("app.rate-limit.otp-per-ip-hour", Integer.class)).isEqualTo(9);
    }
  }
}
