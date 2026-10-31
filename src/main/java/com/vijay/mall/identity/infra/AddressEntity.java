package com.vijay.mall.identity.infra;

import jakarta.persistence.Column;
import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "address")
class AddressEntity extends BaseEntity {
  UUID userId;
  String label;
  String line1;
  String area;
  String pincode;
  @Column(name = "is_default") boolean defaultAddress;
  Instant createdAt;

  protected AddressEntity() {}
}
