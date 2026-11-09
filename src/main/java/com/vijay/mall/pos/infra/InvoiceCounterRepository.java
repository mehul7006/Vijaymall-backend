package com.vijay.mall.pos.infra;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gap-free document numbers. The UPDATE locks the counter row until the surrounding transaction ends, so a bill that
 * fails and rolls back gives its number back and two counters never get the same number.
 */
@Repository
public class InvoiceCounterRepository {
  @PersistenceContext
  private EntityManager em;

  /**
   * The next number for a kind of document ({@code INVOICE}, {@code CREDIT_NOTE}, {@code CREDIT_RECEIPT}) in a
   * financial year, starting at 1 for each year.
   *
   * @param financialYear for example {@code 2026-27}
   */
  @Transactional
  public long next(String kind, String financialYear) {
    em.createNativeQuery("insert into invoice_counter (kind, fy, last_no) values (:k, :f, 0) on conflict (kind, fy) do nothing")
        .setParameter("k", kind).setParameter("f", financialYear).executeUpdate();
    em.createNativeQuery("update invoice_counter set last_no = last_no + 1 where kind = :k and fy = :f")
        .setParameter("k", kind).setParameter("f", financialYear).executeUpdate();
    Number n = (Number) em.createNativeQuery("select last_no from invoice_counter where kind = :k and fy = :f")
        .setParameter("k", kind).setParameter("f", financialYear).getSingleResult();
    return n.longValue();
  }
}
