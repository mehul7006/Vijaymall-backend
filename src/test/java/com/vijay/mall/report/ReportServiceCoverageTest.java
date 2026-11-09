package com.vijay.mall.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.report.app.ReportService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** A native date column can come back as LocalDate, java.sql.Date, java.util.Date or text; every form gives the same day. */
class ReportServiceCoverageTest {
  private static final Instant NOW = Instant.parse("2027-03-10T20:00:00Z"); // 11 March in the shop's time zone

  private static Query scalar(Object value) {
    Query q = mock(Query.class);
    when(q.setParameter(any(String.class), any())).thenReturn(q);
    when(q.getSingleResult()).thenReturn(value);
    return q;
  }

  @Test
  void dayColumnsOfEveryDriverTypeLandOnTheRightDay() {
    EntityManager em = mock(EntityManager.class);
    var service = new ReportService(mock(InventoryService.class), mock(CatalogService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    ReflectionTestUtils.setField(service, "em", em);
    Query days = mock(Query.class);
    when(days.setParameter(any(String.class), any())).thenReturn(days);
    long midnight = java.sql.Date.valueOf("2027-03-10").getTime();
    when(days.getResultList()).thenReturn(List.of(
        new Object[] {new java.util.Date(midnight), 1000L},
        new Object[] {"2027-03-09 00:00:00", 2000L},
        new Object[] {LocalDate.parse("2027-03-08"), 4000L}));
    Query zero = scalar(0L);
    when(em.createNativeQuery(any(String.class))).thenReturn(zero, zero, zero, zero, zero, zero, zero, zero, zero, days);
    var d = service.dashboard();
    assertThat(d.last7Days()).hasSize(7);
    assertThat(d.last7Days().stream().filter(x -> x.date().equals(LocalDate.parse("2027-03-10"))).findFirst().orElseThrow()
        .salesPaise()).isEqualTo(1000);
    assertThat(d.last7Days().stream().filter(x -> x.date().equals(LocalDate.parse("2027-03-09"))).findFirst().orElseThrow()
        .salesPaise()).isEqualTo(2000);
    assertThat(d.last7Days().stream().filter(x -> x.date().equals(LocalDate.parse("2027-03-08"))).findFirst().orElseThrow()
        .salesPaise()).isEqualTo(4000);
    assertThat(d.last7Days().getLast().salesPaise()).isZero();
  }
}
