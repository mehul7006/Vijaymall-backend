package com.vijay.mall.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ManagementPortGuardTest {
  @Test
  void aSeparatePortIsFine() {
    assertThat(ManagementPortGuard.problem("9090", "8080")).isNull();
    assertThat(ManagementPortGuard.problem(" 9090 ", null)).isNull();
    new ManagementPortGuard(new MockEnvironment().withProperty("management.server.port", "9090").withProperty("server.port", "8080"));
  }

  @Test
  void theSamePortOrNoSeparatePortIsRefused() {
    assertThat(ManagementPortGuard.problem("8080", "8080")).contains("MANAGEMENT_PORT");
    assertThat(ManagementPortGuard.problem(null, "8080")).contains("MANAGEMENT_PORT");
    assertThat(ManagementPortGuard.problem("  ", "8080")).contains("MANAGEMENT_PORT");
    assertThat(ManagementPortGuard.problem("8080", null)).contains("MANAGEMENT_PORT");
    assertThatThrownBy(() -> new ManagementPortGuard(new MockEnvironment().withProperty("server.port", "8080")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("MANAGEMENT_PORT");
  }
}
