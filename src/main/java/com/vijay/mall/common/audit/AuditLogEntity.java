package com.vijay.mall.common.audit;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
class AuditLogEntity extends BaseEntity {
  UUID actorId;
  String action;
  String entity;
  String entityId;
  @Column(name = "change") String detail;
  Instant createdAt;

  protected AuditLogEntity() {}
}
