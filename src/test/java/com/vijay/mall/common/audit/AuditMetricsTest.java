package com.vijay.mall.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditMetricsTest {
  @Test
  void everyAuditedActionIsCountedByItsKind() {
    var registry = new SimpleMeterRegistry();
    var audit = new AuditService(mock(AuditLogJpa.class), Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC));
    audit.setMetrics(registry);
    audit.log(UUID.randomUUID(), "SALE", "pos_sale", UUID.randomUUID(), "x");
    audit.log(UUID.randomUUID(), "SALE", "pos_sale", UUID.randomUUID(), "y");
    audit.log(UUID.randomUUID(), "RETURN", "pos_return", null, null);
    assertThat(registry.get("mall.audit.actions").tag("action", "SALE").counter().count()).isEqualTo(2);
    assertThat(registry.get("mall.audit.actions").tag("action", "RETURN").counter().count()).isEqualTo(1);
  }
}
