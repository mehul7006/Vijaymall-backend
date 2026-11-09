package com.vijay.mall.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.logback.LogbackLoggingSystemProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** Feeds application-prod.yml to Spring Boot's own logging code and checks it understood every rolling-policy property. */
class ProdLoggingPropertiesTest {
  private Map<String, String> boot(Map<String, Object> env) throws Exception {
    var e = new StandardEnvironment();
    e.getPropertySources().addFirst(new MapPropertySource("env", env));
    for (var ps : new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yml"))) e.getPropertySources().addLast(ps);
    Map<String, String> out = new HashMap<>();
    new LogbackLoggingSystemProperties(e, out::put).apply(null);
    return out;
  }

  @Test
  void rollsDailyAndBySizeAndKeepsThirtyDaysByDefault() throws Exception {
    var p = boot(Map.of("LOG_DIR", "/var/log/mall"));
    assertThat(p).containsEntry("LOGBACK_ROLLINGPOLICY_FILE_NAME_PATTERN", "/var/log/mall/mall.%d{yyyy-MM-dd}.%i.log.gz")
        .containsEntry("LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE", "20971520")
        .containsEntry("LOGBACK_ROLLINGPOLICY_MAX_HISTORY", "30")
        .containsEntry("LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP", "5368709120")
        .containsEntry("LOGBACK_ROLLINGPOLICY_CLEAN_HISTORY_ON_START", "true");
  }

  @Test
  void retentionComesFromTheEnvironmentVariable() throws Exception {
    assertThat(boot(Map.of("LOG_RETENTION_DAYS", "7"))).containsEntry("LOGBACK_ROLLINGPOLICY_MAX_HISTORY", "7");
  }

  @Test
  void defaultFolderIsLogsNextToTheJar() throws Exception {
    assertThat(boot(Map.of())).containsEntry("LOGBACK_ROLLINGPOLICY_FILE_NAME_PATTERN", "./logs/mall.%d{yyyy-MM-dd}.%i.log.gz");
  }
}
