package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The admin changes a log level on the config screen and the running server follows, without a restart. */
class LogSettingsIT extends IntegrationTestBase {
  private void save(String admin, String key, String value) {
    var res = call(HttpMethod.PUT, "/api/v1/admin/config", admin, Map.of("changes", List.of(Map.of("key", key, "value", value))), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @SuppressWarnings("rawtypes")
  void savingALevelChangesTheRunningLogger() {
    String admin = adminToken();
    try {
      save(admin, "log.level.app", "DEBUG");
      var status = get("/api/v1/admin/logs/status", admin);
      assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
      Map body = status.getBody();
      assertThat(body.get("levelApp")).isEqualTo("DEBUG");
      assertThat(body).containsKeys("levelRoot", "levelSql", "retentionDays", "fileLogging", "folder", "files");
      assertThat(LoggerFactory.getLogger("com.vijay.mall").isDebugEnabled()).isTrue();
    } finally {
      save(admin, "log.level.app", "INFO");
    }
    assertThat(get("/api/v1/admin/logs/status", admin).getBody().get("levelApp")).isEqualTo("INFO");
    assertThat(LoggerFactory.getLogger("com.vijay.mall").isDebugEnabled()).isFalse();
  }

  @Test
  void onlyAnAdminMaySeeTheStatus() {
    assertThat(get("/api/v1/admin/logs/status", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    String customer = token(freshEmail());
    assertThat(get("/api/v1/admin/logs/status", customer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }
}
