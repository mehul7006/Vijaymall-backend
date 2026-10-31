package com.vijay.mall.payment.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "payment_event")
class PaymentEventEntity extends BaseEntity {
  String provider;
  String providerEventId;
  @Column(name = "type") String eventType;
  String payload;
  Instant processedAt;

  protected PaymentEventEntity() {}
}
