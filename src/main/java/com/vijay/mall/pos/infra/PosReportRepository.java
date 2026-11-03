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
  public record Totals(long bills, long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise, long coinsUsed) {}

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

  public Totals sales(Instant from, Instant to, UUID cashier) {
    Object[] r = (Object[]) bind(em.createQuery("select count(s), sum(s.totalPaise), sum(s.taxablePaise), sum(s.cgstPaise), "
        + "sum(s.sgstPaise), sum(s.coinsUsed) from PosSaleEntity s where s.createdAt >= :f and s.createdAt < :t"
        + cashierClause("s", cashier)), from, to, cashier).getSingleResult();
    return new Totals(num(r[0]), num(r[1]), num(r[2]), num(r[3]), num(r[4]), num(r[5]));
  }

  public ReturnTotals returns(Instant from, Instant to, UUID cashier) {
    Object[] r = (Object[]) bind(em.createQuery("select count(x), sum(x.refundPaise) from PosReturnEntity x "
        + "where x.createdAt >= :f and x.createdAt < :t" + cashierClause("x", cashier)), from, to, cashier).getSingleResult();
    return new ReturnTotals(num(r[0]), num(r[1]));
  }

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

  @SuppressWarnings("unchecked")
  public Map<String, Long> refundedByMode(Instant from, Instant to, UUID cashier) {
    List<Object[]> rows = bind(em.createQuery("select p.mode, sum(p.amountPaise) from PosRefundPaymentEntity p "
        + "join PosReturnEntity x on x.id = p.returnId where x.createdAt >= :f and x.createdAt < :t"
        + cashierClause("x", cashier) + " group by p.mode"), from, to, cashier).getResultList();
    return rows.stream().collect(Collectors.toMap(r -> (String) r[0], r -> num(r[1])));
  }
}
