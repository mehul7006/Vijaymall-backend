package com.vijay.mall.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository;
import com.vijay.mall.report.app.ReportService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The dashboard maths with the database mocked: zero-fill, ordering, shop time zone and name lookup. */
class ReportServiceTest {
  /**
   * 2027-03-10 20:00 UTC is already 11 March, 01:30 in Asia/Kolkata
   */
  private static final Instant NOW = Instant.parse("2027-03-10T20:00:00Z");

  private final EntityManager em = mock(EntityManager.class);
  private final InventoryService inventory = mock(InventoryService.class);
  private final CatalogService catalog = mock(CatalogService.class);
  private final ReportService service = new ReportService(inventory, catalog, Clock.fixed(NOW, ZoneOffset.UTC));

  private Query scalar(Object value) {
    Query q = mock(Query.class);
    when(q.setParameter(any(String.class), any())).thenReturn(q);
    when(q.getSingleResult()).thenReturn(value);
    return q;
  }

  @Test
  void usesTheShopCalendarAndFillsMissingDaysWithZero() {
    ReflectionTestUtils.setField(service, "em", em);
    Query sales = scalar(150000L), count = scalar(3L), fresh = scalar(2L), coins = scalar(40L);
    Query posSales = scalar(80000L), posBills = scalar(5L), posRefunds = scalar(1000L), onlineRefunds = scalar(250L);
    Query days = mock(Query.class);
    when(days.setParameter(any(String.class), any())).thenReturn(days);
    when(days.getResultList()).thenReturn(List.of(
        new Object[] {Date.valueOf("2027-03-11"), 90000L},
        new Object[] {Date.valueOf("2027-03-08"), 60000L}));
    when(em.createNativeQuery(any(String.class))).thenReturn(sales, count, fresh, coins, posSales, posBills, posRefunds, onlineRefunds, days);

    UUID low = UUID.randomUUID();
    UUID unnamed = UUID.randomUUID();
    when(inventory.lowStock(10)).thenReturn(List.of(new InventoryRepository.Stock(low, 2, 5),
        new InventoryRepository.Stock(unnamed, 0, 5)));
    when(catalog.names(List.of(low, unnamed))).thenReturn(Map.of(low, "Toothpaste"));

    var d = service.dashboard();
    assertThat(d.salesTodayPaise()).isEqualTo(150000);
    assertThat(d.ordersToday()).isEqualTo(3);
    assertThat(d.newOrders()).isEqualTo(2);
    assertThat(d.coinsIssuedToday()).isEqualTo(40);
    assertThat(d.posSalesTodayPaise()).isEqualTo(80000);
    assertThat(d.posBillsToday()).isEqualTo(5);
    assertThat(d.posRefundsTodayPaise()).isEqualTo(1000);
    assertThat(d.onlineRefundsTodayPaise()).as("refunds on returned online orders are shown too").isEqualTo(250);
    assertThat(d.last7Days()).hasSize(7);
    assertThat(d.last7Days().getFirst().date()).isEqualTo(LocalDate.parse("2027-03-05"));
    assertThat(d.last7Days().getLast().date()).isEqualTo(LocalDate.parse("2027-03-11"));
    assertThat(d.last7Days().getLast().salesPaise()).isEqualTo(90000);
    assertThat(d.last7Days().stream().filter(x -> x.date().equals(LocalDate.parse("2027-03-08"))).findFirst().orElseThrow()
        .salesPaise()).isEqualTo(60000);
    assertThat(d.last7Days().stream().filter(x -> x.salesPaise() == 0)).hasSize(5);
    assertThat(d.lowStock()).extracting(ReportService.LowItem::name).containsExactly("Toothpaste", "?");
    assertThat(d.lowStock()).extracting(ReportService.LowItem::qty).containsExactly(2, 0);
  }
}
