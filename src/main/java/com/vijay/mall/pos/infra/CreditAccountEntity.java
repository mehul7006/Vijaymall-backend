package com.vijay.mall.pos.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** What one customer owes the shop and how much they may owe at most. */
@Entity
@Table(name = "credit_account")
class CreditAccountEntity {
  @Id UUID userId;
  long balancePaise;
  long limitPaise;
  Instant updatedAt;

  protected CreditAccountEntity() {}
}
