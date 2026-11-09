package com.vijay.mall.common.audit;

import com.vijay.mall.common.metrics.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Who changed what. Written in the same transaction as the change. */
@Service
public class AuditService {
  private final AuditLogJpa jpa;
  private final Clock clock;

  AuditService(AuditLogJpa jpa, Clock clock) {
    this.jpa = jpa;
    this.clock = clock;
  }

  private MeterRegistry metrics;

  /** Optional (plain unit tests run without it): counts every audited action, so dashboards show sales, returns, overrides... */
  @Autowired(required = false)
  void setMetrics(MeterRegistry metrics) { this.metrics = metrics; }

  @Transactional
  public void log(UUID actorId, String action, String entity, Object entityId, String change) {
    AuditLogEntity e = new AuditLogEntity();
    e.id = UUID.randomUUID();
    e.actorId = actorId;
    e.action = action;
    e.entity = entity;
    e.entityId = entityId == null ? null : entityId.toString();
    e.detail = change;
    e.createdAt = clock.instant();
    jpa.save(e);
    Metrics.count(metrics, "mall.audit.actions", "Audited actions by kind", "action", action, 1);
  }
}
