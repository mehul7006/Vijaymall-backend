package com.vijay.mall.pos.infra;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/** Aggregates for the cash-up and the dashboard. {@code cashier} null means every cashier. */
@Repository
public class PosReportRepository {
  /**
   * Totals of counter bills in a period: number of bills, takings, taxable value, CGST and SGST in paise, and coins
   * redeemed.
   */
  public record Totals(long bills, long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise, long coinsUsed) {}

  /** Totals of credit notes in a period: how many, and the refunded value in paise. */
  public record ReturnTotals(long notes, long refundPaise) {}

  @PersistenceContext
  private EntityManager em;

  private static String cashierClause(String alias, UUID cashier) { return cashier == null ? "" : " and " + alias + ".cashierId = :c"; }

  private static Query bind(Query q, Instant from, Instant to, UUID cashier) {
    q.setParameter("f", from).setParameter("t", to);
    if (cashier != null) q.setParameter("c", cashier);
    return q;
  }

  private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

  /** Totals of the bills rung in the half-open period {@code [from, to)}, optionally for one cashier. */
  public Totals sales(Instant from, Instant to, UUID cashier) {
    Object[] r = (Object[]) bind(em.createQuery("select count(s), sum(s.totalPaise), sum(s.taxablePaise), sum(s.cgstPaise), "
        + "sum(s.sgstPaise), sum(s.coinsUsed) from PosSaleEntity s where s.createdAt >= :f and s.createdAt < :t"
        + cashierClause("s", cashier)), from, to, cashier).getSingleResult();
    return new Totals(num(r[0]), num(r[1]), num(r[2]), num(r[3]), num(r[4]), num(r[5]));
  }

  /** Totals of the credit notes issued in {@code [from, to)}, optionally for one cashier. */
  public ReturnTotals returns(Instant from, Instant to, UUID cashier) {
    Object[] r = (Object[]) bind(em.createQuery("select count(x), sum(x.refundPaise) from PosReturnEntity x "
        + "where x.createdAt >= :f and x.createdAt < :t" + cashierClause("x", cashier)), from, to, cashier).getSingleResult();
    return new ReturnTotals(num(r[0]), num(r[1]));
  }

  /**
   * Money taken on bills in the period, in paise by payment mode (includes {@code CREDIT} for the part put on
   * account).
   */
  @SuppressWarnings("unchecked")
  public Map<String, Long> paidByMode(Instant from, Instant to, UUID cashier) {
    List<Object[]> rows = bind(em.createQuery("select p.mode, sum(p.amountPaise) from PosPaymentEntity p "
        + "join PosSaleEntity s on s.id = p.saleId where s.createdAt >= :f and s.createdAt < :t"
        + cashierClause("s", cashier) + " group by p.mode"), from, to, cashier).getResultList();
    return rows.stream().collect(Collectors.toMap(r -> (String) r[0], r -> num(r[1])));
  }

  /** Part of the returns that reduced customers' credit instead of being paid out. */
  public long creditReturned(Instant from, Instant to, UUID cashier) {
    return num(bind(em.createQuery("select sum(x.creditAdjustPaise) from PosReturnEntity x where x.createdAt >= :f "
        + "and x.createdAt < :t" + cashierClause("x", cashier)), from, to, cashier).getSingleResult());
  }

  /** Money customers handed over in the period to pay off their credit, by mode. */
  @SuppressWarnings("unchecked")
  public Map<String, Long> creditRecoveredByMode(Instant from, Instant to, UUID cashier) {
    List<Object[]> rows = bind(em.createQuery("select l.mode, sum(l.amountPaise) from CreditPaymentLineEntity l "
        + "join CreditPaymentEntity p on p.id = l.paymentId where p.createdAt >= :f and p.createdAt < :t"
        + cashierClause("p", cashier) + " group by l.mode"), from, to, cashier).getResultList();
    return rows.stream().collect(Collectors.toMap(r -> (String) r[0], r -> num(r[1])));
  }

  /** Money paid out on credit notes in the period, in paise by payment mode. */
  @SuppressWarnings("unchecked")
  public Map<String, Long> refundedByMode(Instant from, Instant to, UUID cashier) {
    List<Object[]> rows = bind(em.createQuery("select p.mode, sum(p.amountPaise) from PosRefundPaymentEntity p "
        + "join PosReturnEntity x on x.id = p.returnId where x.createdAt >= :f and x.createdAt < :t"
        + cashierClause("x", cashier) + " group by p.mode"), from, to, cashier).getResultList();
    return rows.stream().collect(Collectors.toMap(r -> (String) r[0], r -> num(r[1])));
  }

  // ---------- counter controls (loss prevention) ----------
  /** Per cashier: bills, takings, and how many bills had a manual discount and how much it came to, in paise. */
  public record CashierSales(UUID cashierId, long bills, long totalPaise, long manualDiscounts, long manualDiscountPaise) {}

  /** Per cashier: credit notes issued and the refunded value in paise. */
  public record CashierReturns(UUID cashierId, long notes, long refundPaise) {}

  /** Per cashier: shifts closed and the sum of their cash differences in paise. */
  public record CashierShifts(UUID cashierId, long closed, long variancePaise) {}

  /** Bills and manual discounts per cashier in {@code [from, to)}, for spotting unusual discounting. */
  @SuppressWarnings("unchecked")
  public List<CashierSales> salesByCashier(Instant from, Instant to) {
    List<Object[]> rows = em.createQuery("select s.cashierId, count(s), sum(s.totalPaise), "
        + "sum(case when s.manualDiscountPaise > 0 then 1 else 0 end), sum(s.manualDiscountPaise) from PosSaleEntity s "
        + "where s.createdAt >= :f and s.createdAt < :t group by s.cashierId").setParameter("f", from).setParameter("t", to).getResultList();
    return rows.stream().map(r -> new CashierSales((UUID) r[0], num(r[1]), num(r[2]), num(r[3]), num(r[4]))).toList();
  }

  /** Credit notes per cashier in {@code [from, to)}, for spotting unusual refunds. */
  @SuppressWarnings("unchecked")
  public List<CashierReturns> returnsByCashier(Instant from, Instant to) {
    List<Object[]> rows = em.createQuery("select x.cashierId, count(x), sum(x.refundPaise) from PosReturnEntity x "
        + "where x.createdAt >= :f and x.createdAt < :t group by x.cashierId").setParameter("f", from).setParameter("t", to).getResultList();
    return rows.stream().map(r -> new CashierReturns((UUID) r[0], num(r[1]), num(r[2]))).toList();
  }

  /** Shifts closed in the period and the total of their cash differences (negative = drawer was short). */
  @SuppressWarnings("unchecked")
  public List<CashierShifts> shiftsByCashier(Instant from, Instant to) {
    List<Object[]> rows = em.createQuery("select h.cashierId, count(h), sum(h.variancePaise) from PosShiftEntity h "
        + "where h.closedAt >= :f and h.closedAt < :t group by h.cashierId").setParameter("f", from).setParameter("t", to).getResultList();
    return rows.stream().map(r -> new CashierShifts((UUID) r[0], num(r[1]), num(r[2]))).toList();
  }
}
