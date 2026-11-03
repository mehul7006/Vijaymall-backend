package com.vijay.mall.pos.infra;

import com.vijay.mall.common.Page;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Customer credit: the account, its ledger, and the repayments taken at the counter. */
@Repository
public class CreditRepository {
  public record Account(UUID userId, long balancePaise, long limitPaise) {}

  public record Entry(UUID id, String kind, long deltaPaise, long balanceAfterPaise, String refNo, UUID saleId,
                      UUID returnId, UUID paymentId, String note, UUID actorId, Instant at) {}

  public record Repayment(UUID id, String receiptNo, UUID userId, UUID cashierId, long amountPaise, String note,
                          Instant at, List<Line> lines) {}

  public record Line(String mode, long amountPaise, String reference) {}

  private final CreditJpa accounts;
  private final CreditLedgerJpa ledgerJpa;
  private final CreditPaymentJpa payments;
  private final CreditPaymentLineJpa lines;

  CreditRepository(CreditJpa accounts, CreditLedgerJpa ledgerJpa, CreditPaymentJpa payments, CreditPaymentLineJpa lines) {
    this.accounts = accounts;
    this.ledgerJpa = ledgerJpa;
    this.payments = payments;
    this.lines = lines;
  }

  /** The balance and limit, or zeros for a customer who never had credit. No lock. */
  public Account find(UUID userId) {
    return accounts.findById(userId).map(e -> new Account(e.userId, e.balancePaise, e.limitPaise))
        .orElse(new Account(userId, 0, 0));
  }

  /** Same, but holds the row until the transaction ends so two bills cannot overspend one limit. */
  @Transactional
  public Account lock(UUID userId, Instant now) {
    accounts.createIfAbsent(userId, now);
    CreditAccountEntity e = accounts.lockById(userId).orElseThrow();
    return new Account(e.userId, e.balancePaise, e.limitPaise);
  }

  @Transactional
  public void setLimit(UUID userId, long limitPaise, Instant now) {
    accounts.createIfAbsent(userId, now);
    CreditAccountEntity e = accounts.lockById(userId).orElseThrow();
    e.limitPaise = limitPaise;
    e.updatedAt = now;
  }

  /**
   * Moves the balance by {@code delta} and writes the ledger line. The caller must hold the lock from {@link #lock}.
   * The balance can never go below zero.
   */
  @Transactional
  public Entry append(UUID userId, String kind, long delta, String refNo, UUID saleId, UUID returnId, UUID paymentId,
                      String note, UUID actorId, Instant now) {
    CreditAccountEntity a = accounts.lockById(userId).orElseThrow();
    long after = a.balancePaise + delta;
    if (after < 0) throw new IllegalStateException("credit balance would go below zero");
    a.balancePaise = after;
    a.updatedAt = now;
    CreditLedgerEntity e = new CreditLedgerEntity();
    e.id = UUID.randomUUID();
    e.userId = userId;
    e.kind = kind;
    e.deltaPaise = delta;
    e.balanceAfterPaise = after;
    e.refNo = refNo;
    e.saleId = saleId;
    e.returnId = returnId;
    e.paymentId = paymentId;
    e.note = note == null ? "" : note;
    e.actorId = actorId;
    e.createdAt = now;
    return toEntry(ledgerJpa.save(e));
  }

  public long returnedAgainst(UUID saleId) { return ledgerJpa.returnedAgainst(saleId); }

  /** Newest first. */
  public Page<Entry> ledger(UUID userId, int page, int size) {
    var result = ledgerJpa.findByUserId(userId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    return new Page<>(result.getContent().stream().map(CreditRepository::toEntry).toList(), page, size, result.getTotalElements());
  }

  private static Entry toEntry(CreditLedgerEntity e) {
    return new Entry(e.id, e.kind, e.deltaPaise, e.balanceAfterPaise, e.refNo, e.saleId, e.returnId, e.paymentId, e.note,
        e.actorId, e.createdAt);
  }

  // ---------- repayments ----------
  @Transactional
  public UUID insertRepayment(UUID id, String receiptNo, String idemKey, UUID userId, UUID cashierId, long amount,
                              String note, Instant now, List<Line> parts) {
    CreditPaymentEntity e = new CreditPaymentEntity();
    e.id = id;
    e.receiptNo = receiptNo;
    e.idemKey = idemKey;
    e.userId = userId;
    e.cashierId = cashierId;
    e.amountPaise = amount;
    e.note = note == null ? "" : note;
    e.createdAt = now;
    payments.saveAndFlush(e);
    parts.forEach(p -> {
      CreditPaymentLineEntity l = new CreditPaymentLineEntity();
      l.id = UUID.randomUUID();
      l.paymentId = id;
      l.mode = p.mode();
      l.amountPaise = p.amountPaise();
      l.reference = p.reference();
      lines.save(l);
    });
    lines.flush();
    return id;
  }

  public Optional<Repayment> findRepaymentByIdem(String idemKey) {
    return payments.findByIdemKey(idemKey).map(this::assemble);
  }

  public Optional<Repayment> findRepayment(UUID id) { return payments.findById(id).map(this::assemble); }

  private Repayment assemble(CreditPaymentEntity e) {
    List<Line> parts = lines.findByPaymentIdIn(List.of(e.id)).stream()
        .map(l -> new Line(l.mode, l.amountPaise, l.reference)).toList();
    return new Repayment(e.id, e.receiptNo, e.userId, e.cashierId, e.amountPaise, e.note, e.createdAt, parts);
  }
}
