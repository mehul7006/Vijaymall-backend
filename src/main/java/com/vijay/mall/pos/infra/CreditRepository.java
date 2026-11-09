package com.vijay.mall.pos.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashMap;
import java.util.Map;
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
  /** A customer's credit account: the amount owed and the limit, in paise (a limit of 0 means credit is off). */
  public record Account(UUID userId, long balancePaise, long limitPaise) {}

  /**
   * One credit ledger line: its kind, the change in paise (positive means the customer owes more) and the balance
   * after it, with the bill, return or repayment it came from.
   */
  public record Entry(UUID id, String kind, long deltaPaise, long balanceAfterPaise, String refNo, UUID saleId,
                      UUID returnId, UUID paymentId, String note, UUID actorId, Instant at) {}

  /** A repayment taken at the counter: receipt number, customer, cashier, total in paise and its payment parts. */
  public record Repayment(UUID id, String receiptNo, UUID userId, UUID cashierId, long amountPaise, String note,
                          Instant at, List<Line> lines) {}

  /** One payment part of a repayment: mode, amount in paise and reference. */
  public record Line(String mode, long amountPaise, String reference) {}

  /**
   * A customer the till can pick for credit: who they are and where their account stands, in paise.
   * @param blocked whether the customer's account is blocked
   */
  public record Pick(UUID userId, String name, String mobile, long balancePaise, long limitPaise, boolean blocked) {}

  @PersistenceContext
  private EntityManager em;

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

  /** Sets the most the customer may owe, in paise, creating the account row if needed. 0 switches credit off. */
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

  /** How much credit earlier returns of this bill already cancelled, in paise. */
  public long returnedAgainst(UUID saleId) { return ledgerJpa.returnedAgainst(saleId); }

  /** The ledger line written by a repayment (it holds the balance after the payment), if any. */
  public Optional<Entry> entryOfPayment(UUID paymentId) { return ledgerJpa.findFirstByPaymentId(paymentId).map(CreditRepository::toEntry); }

  /** Newest first. */
  public Page<Entry> ledger(UUID userId, int page, int size) {
    var result = ledgerJpa.findByUserId(userId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    return new Page<>(result.getContent().stream().map(CreditRepository::toEntry).toList(), page, size, result.getTotalElements());
  }

  /**
   * Customers for the till's credit picker, by name. Either the customers with a credit limit, or (with {@code everyone}) every
   * customer; {@code like} narrows to a name or mobile that contains it. One extra row tells whether another page follows and the
   * total is counted only up to {@link BoundedCount#CAP}.
   * @param like lower-case pattern with {@code %} around it, or {@code null} for no search
   * @param everyone true to include customers who have no credit limit (they have no account row)
   */
  @SuppressWarnings("unchecked")
  public Page<Pick> pick(String like, boolean everyone, int page, int size) {
    StringBuilder from = new StringBuilder("users u ").append(everyone ? "left join credit_account a on a.user_id = u.id where u.role = 'CUSTOMER'"
        : "join credit_account a on a.user_id = u.id where a.limit_paise > 0");
    Map<String, Object> params = new HashMap<>();
    if (like != null) {
      from.append(" and (lower(coalesce(u.name, '')) like :pattern or coalesce(u.mobile, '') like :pattern)");
      params.put("pattern", like);
    }
    var query = em.createNativeQuery("select u.id, u.name, u.mobile, coalesce(a.balance_paise, 0), coalesce(a.limit_paise, 0), "
        + "u.status = 'BLOCKED' from " + from + " order by lower(coalesce(u.name, '')), u.id limit :n offset :o");
    params.forEach(query::setParameter);
    query.setParameter("n", size + 1).setParameter("o", (long) page * size);
    List<Object[]> found = query.getResultList();
    List<Pick> rows = found.stream().map(r -> new Pick(r[0] instanceof UUID id ? id : UUID.fromString(r[0].toString()), (String) r[1],
        (String) r[2], ((Number) r[3]).longValue(), ((Number) r[4]).longValue(), (Boolean) r[5])).toList();
    return BoundedCount.assemble(rows, page, size, () -> BoundedCount.count(em, from.toString(), params));
  }

  private static Entry toEntry(CreditLedgerEntity e) {
    return new Entry(e.id, e.kind, e.deltaPaise, e.balanceAfterPaise, e.refNo, e.saleId, e.returnId, e.paymentId, e.note,
        e.actorId, e.createdAt);
  }

  // ---------- repayments ----------
  /**
   * Stores a repayment with its payment parts and returns its id. A second insert with the same idempotency key fails
   * on the unique key; the caller then reads the first one.
   */
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

  /** The repayment taken with this idempotency key, if any. */
  public Optional<Repayment> findRepaymentByIdem(String idemKey) {
    return payments.findByIdemKey(idemKey).map(this::assemble);
  }

  /** One repayment by id. */
  public Optional<Repayment> findRepayment(UUID id) { return payments.findById(id).map(this::assemble); }

  private Repayment assemble(CreditPaymentEntity e) {
    List<Line> parts = lines.findByPaymentIdIn(List.of(e.id)).stream()
        .map(l -> new Line(l.mode, l.amountPaise, l.reference)).toList();
    return new Repayment(e.id, e.receiptNo, e.userId, e.cashierId, e.amountPaise, e.note, e.createdAt, parts);
  }
}
