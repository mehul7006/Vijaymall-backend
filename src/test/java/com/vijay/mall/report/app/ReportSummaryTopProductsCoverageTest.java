package com.vijay.mall.report.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReportSummaryTopProductsCoverageTest {
  private static Query query(Object single, List<Object[]> rows) {
    Query q = mock(Query.class);
    when(q.setParameter(any(String.class), any())).thenReturn(q);
    when(q.getSingleResult()).thenReturn(single);
    when(q.getResultList()).thenReturn((List) rows);
    return q;
  }

  @Test void topProducts_mergeOnlineAndCounterSales_whetherTheIdComesAsUuidOrText() {
    UUID rice = UUID.randomUUID(), dal = UUID.randomUUID();
    EntityManager em = mock(EntityManager.class);
    Query online = query(0L, List.<Object[]>of(new Object[] {rice, "Rice", 3L, 30000L}, new Object[] {dal.toString(), "Dal", 1L, 9000L}));
    Query counter = query(0L, List.<Object[]>of(new Object[] {rice.toString(), "Rice", 2L, 20000L}));
    Query other = query(0L, List.of());
    when(em.createNativeQuery(any(String.class))).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if (sql.contains("FROM order_item")) return online;
      if (sql.contains("FROM pos_sale_item")) return counter;
      return other;
    });
    var service = new ReportSummaryService(Clock.fixed(Instant.parse("2027-03-10T06:00:00Z"), ZoneOffset.UTC));
    ReflectionTestUtils.setField(service, "em", em);

    var summary = service.summary("week", null, LocalDate.parse("2027-03-10"));

    assertThat(summary.topProducts()).hasSize(2);
    assertThat(summary.topProducts().get(0)).isEqualTo(new ReportSummaryService.TopProduct(rice, "Rice", 5, 50000));
    assertThat(summary.topProducts().get(1)).isEqualTo(new ReportSummaryService.TopProduct(dal, "Dal", 1, 9000));
    assertThat(summary.byDay()).hasSize(7);
    assertThat(summary.salesPaise()).isZero();
  }
}
