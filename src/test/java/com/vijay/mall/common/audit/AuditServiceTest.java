package com.vijay.mall.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditServiceTest {
  private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
  private final AuditLogJpa jpa = mock(AuditLogJpa.class);
  private final AuditService audit = new AuditService(jpa, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void writesWhoWhatAndWhen() {
    UUID actor = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    audit.log(actor, "PRODUCT_UPDATE", "product", entity, "price 10 -> 12");
    var cap = ArgumentCaptor.forClass(AuditLogEntity.class);
    verify(jpa).save(cap.capture());
    var e = cap.getValue();
    assertThat(e.id).isNotNull();
    assertThat(e.actorId).isEqualTo(actor);
    assertThat(e.action).isEqualTo("PRODUCT_UPDATE");
    assertThat(e.entity).isEqualTo("product");
    assertThat(e.entityId).isEqualTo(entity.toString());
    assertThat(e.detail).isEqualTo("price 10 -> 12");
    assertThat(e.createdAt).isEqualTo(NOW);
  }

  @Test
  void entityIdMayBeMissing() {
    audit.log(null, "SETTINGS", "coin", null, "x");
    var cap = ArgumentCaptor.forClass(AuditLogEntity.class);
    verify(jpa).save(cap.capture());
    assertThat(cap.getValue().entityId).isNull();
    assertThat(cap.getValue().actorId).isNull();
  }
}
