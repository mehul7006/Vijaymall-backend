package com.vijay.mall.pos.infra;

import com.vijay.mall.common.Page;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PosShiftRepository {
  /** closedAt and the three cash figures are null while the shift is open. */
  public record Shift(UUID id, UUID cashierId, Instant openedAt, long openingFloatPaise, Instant closedAt,
                      Long expectedCashPaise, Long countedCashPaise, Long variancePaise, String note) {
    public boolean open() { return closedAt == null; }
  }

  private final PosShiftJpa jpa;

  PosShiftRepository(PosShiftJpa jpa) { this.jpa = jpa; }

  private static Shift toDomain(PosShiftEntity e) {
    return new Shift(e.id, e.cashierId, e.openedAt, e.openingFloatPaise, e.closedAt, e.expectedCashPaise, e.countedCashPaise,
        e.variancePaise, e.note);
  }

  public Optional<Shift> open(UUID cashierId) {
    return jpa.findFirstByCashierIdAndClosedAtIsNull(cashierId).map(PosShiftRepository::toDomain);
  }

  public Optional<Shift> find(UUID id) { return jpa.findById(id).map(PosShiftRepository::toDomain); }

  /** Flushes at once so a second open shift for the same cashier fails here (unique index), inside the caller's transaction. */
  @Transactional
  public Shift insert(UUID cashierId, long openingFloat, Instant now) {
    PosShiftEntity e = new PosShiftEntity();
    e.id = UUID.randomUUID();
    e.cashierId = cashierId;
    e.openedAt = now;
    e.openingFloatPaise = openingFloat;
    return toDomain(jpa.saveAndFlush(e));
  }

  @Transactional
  public void close(UUID id, Instant now, long expected, long counted, String note) {
    jpa.findById(id).ifPresent(e -> {
      e.closedAt = now;
      e.expectedCashPaise = expected;
      e.countedCashPaise = counted;
      e.variancePaise = counted - expected;
      e.note = note;
    });
  }

  /** Newest first; cashierId null means every cashier. */
  public Page<Shift> page(UUID cashierId, int page, int size) {
    PageRequest req = PageRequest.of(page, size);
    var result = cashierId == null ? jpa.findAllByOrderByOpenedAtDesc(req) : jpa.findByCashierIdOrderByOpenedAtDesc(cashierId, req);
    return new Page<>(result.getContent().stream().map(PosShiftRepository::toDomain).toList(), page, size, result.getTotalElements());
  }
}
