package com.vijay.mall.identity.infra;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OtpRepository {
  public record Challenge(UUID id, String otpHash, Instant expiresAt, int attempts) {}

  private final OtpJpa jpa;

  OtpRepository(OtpJpa jpa) { this.jpa = jpa; }

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

  public Optional<Challenge> latestOpen(String email) {
    return jpa.findFirstByEmailAndConsumedFalseOrderByCreatedAtDesc(email)
        .map(e -> new Challenge(e.id, e.otpHash, e.expiresAt, e.attempts));
  }

  @Transactional
  public void addAttempt(UUID id) { jpa.addAttempt(id); }

  @Transactional
  public void consume(UUID id) { jpa.consume(id); }

  @Transactional
  public void deleteOlderThan(Instant cutoff) { jpa.deleteOlderThan(cutoff); }
}
