package com.vijay.mall.report.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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

  @Test void debtors_everyoneWithAnAccount_readsUuidsAsObjectOrText_andNullAmountsAsZero() {
    ReflectionTestUtils.setField(service, "em", em);
    UUID asObject = UUID.randomUUID(), asText = UUID.randomUUID();
    Query count = query(2L, List.of());
    Query rows = query(null, List.of(
        new Object[] {asObject, "Asha", "9000000001", 5000L, 10000L, java.sql.Timestamp.from(AT), null},
        new Object[] {asText.toString(), "Bina", "9000000002", null, null, null, AT}));
    when(em.createNativeQuery(contains("COUNT(*)"))).thenReturn(count);
    when(em.createNativeQuery(contains("ORDER BY"))).thenReturn(rows);

    var page = service.debtors(false, -3, 500);

    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(50);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).customerId()).isEqualTo(asObject);
    assertThat(page.items().get(0).lastSaleAt()).isEqualTo(AT);
    assertThat(page.items().get(0).lastPaymentAt()).isNull();
    assertThat(page.items().get(1).customerId()).isEqualTo(asText);
    assertThat(page.items().get(1).balancePaise()).isZero();
    assertThat(page.items().get(1).limitPaise()).isZero();
    assertThat(page.items().get(1).lastPaymentAt()).isEqualTo(AT);
    verify(rows).setParameter("n", 50);
    verify(rows).setParameter("o", 0);
  }

  @Test void debtors_outstandingOnly_addsTheBalanceFilterToBothQueries() {
    ReflectionTestUtils.setField(service, "em", em);
    Query count = query(0L, List.of());
    Query rows = query(null, List.of());
    when(em.createNativeQuery(contains("a.balance_paise > 0"))).thenReturn(count, rows);
    var page = service.debtors(true, 2, 10);
    assertThat(page.items()).isEmpty();
    assertThat(page.total()).isZero();
    assertThat(page.page()).isEqualTo(2);
    verify(rows).setParameter("o", 20);
  }
}
