package com.vijay.mall.identity.infra;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores the hashed one-time codes sent for sign-in, by e-mail or by mobile number. */
@Repository
public class OtpRepository {
  /**
   * A stored code as the OTP services need it to verify an attempt.
   * @param id row id
   * @param otpHash HMAC of the code
   * @param expiresAt when the code stops being valid
   * @param attempts wrong tries so far
   */
  public record Challenge(UUID id, String otpHash, Instant expiresAt, int attempts) {}

  private final OtpJpa jpa;

  OtpRepository(OtpJpa jpa) { this.jpa = jpa; }

  /** Stores a code sent to an e-mail address. */
  @Transactional
  public void insert(String email, String hash, Instant expires, Instant now) {
    OtpChallengeEntity e = new OtpChallengeEntity();
    e.id = UUID.randomUUID();
    e.email = email;
    e.otpHash = hash;
    e.expiresAt = expires;
    e.createdAt = now;
    jpa.save(e);
  }

  /** A code sent to a mobile number; the email column stays empty. */
  @Transactional
  public void insertMobile(String mobile, String hash, Instant expires, Instant now) {
    OtpChallengeEntity e = new OtpChallengeEntity();
    e.id = UUID.randomUUID();
    e.mobile = mobile;
    e.otpHash = hash;
    e.expiresAt = expires;
    e.createdAt = now;
    jpa.save(e);
  }

  /** The newest code for this mobile number that has not been used yet. */
  public Optional<Challenge> latestOpenMobile(String mobile) {
    return jpa.findFirstByMobileAndConsumedFalseOrderByCreatedAtDesc(mobile)
        .map(e -> new Challenge(e.id, e.otpHash, e.expiresAt, e.attempts));
  }

  /** The newest code for this e-mail address that has not been used yet. */
  public Optional<Challenge> latestOpen(String email) {
    return jpa.findFirstByEmailAndConsumedFalseOrderByCreatedAtDesc(email)
        .map(e -> new Challenge(e.id, e.otpHash, e.expiresAt, e.attempts));
  }

  /** Counts one wrong try against the code. */
  @Transactional
  public void addAttempt(UUID id) { jpa.addAttempt(id); }

  /** Marks the code as used so it can never be accepted again. */
  @Transactional
  public void consume(UUID id) { jpa.consume(id); }

  /** Deletes codes created before the cut-off (nightly cleanup). */
  @Transactional
  public void deleteOlderThan(Instant cutoff) { jpa.deleteOlderThan(cutoff); }
}
