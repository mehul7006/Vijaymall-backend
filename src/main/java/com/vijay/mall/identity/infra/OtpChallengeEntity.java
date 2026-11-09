package com.vijay.mall.identity.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Table {@code otp_challenge}: a hashed one-time code sent to an e-mail address or a mobile number, with its expiry, wrong-try count and consumed flag.
 */
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

  /** Required by JPA; not for application code. */
  protected OtpChallengeEntity() {}
}
