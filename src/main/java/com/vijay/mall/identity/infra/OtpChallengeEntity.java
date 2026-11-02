package com.vijay.mall.identity.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "otp_challenge")
class OtpChallengeEntity extends BaseEntity {
  String email;
  String mobile;
  String otpHash;
  Instant expiresAt;
  int attempts;
  boolean consumed;
  Instant createdAt;

  protected OtpChallengeEntity() {}
}
