package com.vijay.mall.common.audit;

import java.time.Clock;
import java.util.UUID;
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
  }
}
