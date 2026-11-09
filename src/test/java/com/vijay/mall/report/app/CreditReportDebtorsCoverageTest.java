package com.vijay.mall.report.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CreditReportDebtorsCoverageTest {
  private static final Instant AT = Instant.parse("2027-03-01T10:00:00Z");
  private final EntityManager em = mock(EntityManager.class);
  private final CreditReportService service = new CreditReportService(Clock.fixed(AT, ZoneOffset.UTC));

  private Query query(Object single, List<Object[]> rows) {
    Query q = mock(Query.class);
    when(q.setParameter(any(String.class), any())).thenReturn(q);
    when(q.getSingleResult()).thenReturn(single);
    when(q.getResultList()).thenReturn((List) rows);
    return q;
  }

  @Test void debtors_everyoneWithAnAccount_readsUuidsAsObjectOrText_nullAmountsAsZero_andAvailableNeverBelowZero() {
    ReflectionTestUtils.setField(service, "em", em);
    UUID asObject = UUID.randomUUID(), asText = UUID.randomUUID(), over = UUID.randomUUID();
    Query rows = query(null, List.of(
        new Object[] {asObject, "Asha", "9000000001", 5000L, 10000L, false, Timestamp.from(AT), null},
        new Object[] {asText.toString(), "Bina", "9000000002", null, null, true, null, AT},
        new Object[] {over, "Chitra", "9000000003", 9000L, 4000L, null, null, null}));
    when(em.createNativeQuery(contains("ORDER BY"))).thenReturn(rows);

    var page = service.debtors(false, false, null, -3, 500);

    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(50);
    assertThat(page.total()).as("a short page settles the total without a count").isEqualTo(3);
    assertThat(page.items()).hasSize(3);
    assertThat(page.items().get(0).customerId()).isEqualTo(asObject);
    assertThat(page.items().get(0).availablePaise()).isEqualTo(5000);
    assertThat(page.items().get(0).blocked()).isFalse();
    assertThat(page.items().get(0).lastSaleAt()).isEqualTo(AT);
    assertThat(page.items().get(0).lastPaymentAt()).isNull();
    assertThat(page.items().get(1).customerId()).isEqualTo(asText);
    assertThat(page.items().get(1).balancePaise()).isZero();
    assertThat(page.items().get(1).limitPaise()).isZero();
    assertThat(page.items().get(1).availablePaise()).isZero();
    assertThat(page.items().get(1).blocked()).isTrue();
    assertThat(page.items().get(1).lastPaymentAt()).isEqualTo(AT);
    assertThat(page.items().get(2).availablePaise()).as("over the limit: nothing left, never negative").isZero();
    assertThat(page.items().get(2).blocked()).isFalse();
    verify(rows).setParameter("n", 51);
    verify(rows).setParameter("o", 0);
  }

  @Test void debtors_outstandingEligibleAndSearch_addTheirFiltersToBothQueriesWithEscapedText() {
    ReflectionTestUtils.setField(service, "em", em);
    Query count = query(25L, List.of());
    Object[] one = {UUID.randomUUID(), "Asha", "9000000001", 5000L, 10000L, false, null, null};
    Query rows = query(null, List.of(one, one, one));
    when(em.createNativeQuery(contains("ORDER BY"))).thenReturn(rows);
    when(em.createNativeQuery(contains("bounded"))).thenReturn(count);
    var page = service.debtors(true, true, " A_s%HA ", 1, 2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.total()).isEqualTo(25);
    assertThat(page.totalExact()).isTrue();
    verify(rows).setParameter("pattern", "%asha%");
    verify(rows).setParameter("o", 2);
    verify(count).setParameter("pattern", "%asha%");
    verify(em).createNativeQuery("select count(*) from (select 1 from credit_account a JOIN users u ON u.id = a.user_id WHERE 1 = 1 "
        + "AND a.balance_paise > 0 AND a.limit_paise > 0 AND (lower(coalesce(u.name, '')) LIKE :pattern OR coalesce(u.mobile, '') LIKE :pattern) limit 10001) bounded");
  }

  @Test void debtors_aBlankSearchIsNoSearchAndAVeryLongOneIsRefused() {
    ReflectionTestUtils.setField(service, "em", em);
    Query rows = query(null, List.of());
    when(em.createNativeQuery(contains("ORDER BY"))).thenReturn(rows);
    assertThat(service.debtors(false, false, "  ", 0, 10).items()).isEmpty();
    verify(em).createNativeQuery(org.mockito.ArgumentMatchers.argThat(sql -> !sql.contains("LIKE")));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.debtors(false, false, "x".repeat(101), 0, 10))
        .isInstanceOfSatisfying(com.vijay.mall.common.error.ApiException.class, e -> assertThat(e.details().toString()).contains("q=must be at most 100"));
  }
}
