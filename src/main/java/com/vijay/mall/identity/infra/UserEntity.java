package com.vijay.mall.identity.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Persistence shape of a user. Stays inside the infra package; the rest of the app uses the User record. */
@Entity
@Table(name = "users")
class UserEntity extends BaseEntity {
  String email;
  String name;
  String mobile;
  boolean mobileVerified;
  String role;
  String status;
  String source = "APP";
  Instant createdAt;
  Instant updatedAt;

  protected UserEntity() {}
}
