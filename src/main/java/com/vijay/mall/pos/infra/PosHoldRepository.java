package com.vijay.mall.pos.infra;

import com.vijay.mall.pos.domain.PosRecords.HoldRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Parked baskets: stores, lists and deletes the baskets cashiers set aside at the till. */
@Repository
public class PosHoldRepository {
  private final PosHoldJpa jpa;

  PosHoldRepository(PosHoldJpa jpa) { this.jpa = jpa; }

  private static HoldRecord toDomain(PosHoldEntity e) {
    return new HoldRecord(e.id, e.cashierId, e.label, e.customerMobile, e.payload, e.createdAt);
  }

  /** Stores a parked basket and returns it. */
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

  /** The most baskets the list returns: a counter never has this many parked at once, and the list must stay quick if forgotten ones pile up. */
  public static final int MAX_LISTED = 100;

  /** The newest {@link #MAX_LISTED} parked baskets, newest first (shared by all cashiers). */
  public List<HoldRecord> all() {
    return jpa.findAllByOrderByCreatedAtDesc(PageRequest.of(0, MAX_LISTED)).stream().map(PosHoldRepository::toDomain).toList();
  }

  /** One parked basket by id. */
  public Optional<HoldRecord> find(UUID id) { return jpa.findById(id).map(PosHoldRepository::toDomain); }

  /** Deletes a parked basket; false when there was none. */
  @Transactional
  public boolean delete(UUID id) {
    boolean exists = jpa.existsById(id);
    if (exists) jpa.deleteById(id);
    return exists;
  }
}
