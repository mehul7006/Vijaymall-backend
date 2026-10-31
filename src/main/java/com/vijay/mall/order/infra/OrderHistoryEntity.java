package com.vijay.mall.order.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_status_history")
class OrderHistoryEntity extends BaseEntity {
  UUID orderId;
  String fromStatus;
  String toStatus;
  UUID actorId;
  String note;
  Instant createdAt;

  protected OrderHistoryEntity() {}
}
