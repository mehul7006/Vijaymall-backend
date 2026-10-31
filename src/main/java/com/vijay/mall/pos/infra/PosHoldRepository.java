package com.vijay.mall.pos.infra;

import com.vijay.mall.pos.domain.PosRecords.HoldRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PosHoldRepository {
  private final PosHoldJpa jpa;

  PosHoldRepository(PosHoldJpa jpa) { this.jpa = jpa; }

  private static HoldRecord toDomain(PosHoldEntity e) {
    return new HoldRecord(e.id, e.cashierId, e.label, e.customerMobile, e.payload, e.createdAt);
  }

  @Transactional
  public HoldRecord insert(UUID cashierId, String label, String mobile, String payload, Instant now) {
    PosHoldEntity e = new PosHoldEntity();
    e.id = UUID.randomUUID();
    e.cashierId = cashierId;
    e.label = label;
    e.customerMobile = mobile;
    e.payload = payload;
    e.createdAt = now;
    return toDomain(jpa.save(e));
  }

  public List<HoldRecord> all() { return jpa.findAllByOrderByCreatedAtDesc().stream().map(PosHoldRepository::toDomain).toList(); }

  public Optional<HoldRecord> find(UUID id) { return jpa.findById(id).map(PosHoldRepository::toDomain); }

  @Transactional
  public boolean delete(UUID id) {
    boolean exists = jpa.existsById(id);
    if (exists) jpa.deleteById(id);
    return exists;
  }
}
