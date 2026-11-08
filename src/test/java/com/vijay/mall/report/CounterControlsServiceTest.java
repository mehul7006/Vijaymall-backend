package com.vijay.mall.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.pos.infra.PosReportRepository;
import com.vijay.mall.report.app.CounterControlsService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class CounterControlsServiceTest {
  private final UUID asha = UUID.randomUUID();
  private final UUID ravi = UUID.randomUUID();
  private final UUID ghost = UUID.randomUUID();
  private PosReportRepository repo;
  private UserRepository users;
  private CounterControlsService service;

  @BeforeEach
  void setUp() {
    repo = mock(PosReportRepository.class);
    users = mock(UserRepository.class);
    service = new CounterControlsService(repo, users, Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC));
    when(users.findAllByIds(any())).thenReturn(List.of(new User(asha, "asha@shop.in", "Asha", null, "CASHIER"),
        new User(ravi, "ravi@shop.in", " ", null, "CASHIER")));
  }

  @Test
  void eachCashierGetsTheirOwnRatiosAndTheBiggestSellerComesFirst() {
    when(repo.salesByCashier(any(), any())).thenReturn(List.of(new PosReportRepository.CashierSales(asha, 10, 100_000, 2, 5_000),
        new PosReportRepository.CashierSales(ravi, 30, 400_000, 0, 0)));
    when(repo.returnsByCashier(any(), any())).thenReturn(List.of(new PosReportRepository.CashierReturns(asha, 1, 2_500)));
    when(repo.shiftsByCashier(any(), any())).thenReturn(List.of(new PosReportRepository.CashierShifts(ravi, 3, -700)));

    var report = service.report("week", null, null);
    assertThat(report.from()).isEqualTo(LocalDate.of(2026, 11, 2));
    assertThat(report.to()).isEqualTo(LocalDate.of(2026, 11, 8));
    assertThat(report.cashiers()).extracting(CounterControlsService.Row::cashierId).containsExactly(ravi, asha);
    var a = report.cashiers().get(1);
    assertThat(a.name()).isEqualTo("Asha");
    assertThat(a.discountPercentOfSales()).isEqualTo(5.0);
    assertThat(a.refundPercentOfSales()).isEqualTo(2.5);
    assertThat(a.returns()).isEqualTo(1);
    var r = report.cashiers().get(0);
    assertThat(r.name()).as("no name: falls back to the e-mail").isEqualTo("ravi@shop.in");
    assertThat(r.shiftVariancePaise()).isEqualTo(-700);
    assertThat(r.shiftsClosed()).isEqualTo(3);
    assertThat(r.manualDiscounts()).isZero();
  }

  @Test
  void someoneWhoOnlyClosedAShiftOrIsUnknownStillAppearsWithZeros() {
    when(repo.salesByCashier(any(), any())).thenReturn(List.of());
    when(repo.returnsByCashier(any(), any())).thenReturn(List.of(new PosReportRepository.CashierReturns(ghost, 2, 900)));
    when(repo.shiftsByCashier(any(), any())).thenReturn(List.of());
    var row = service.report(null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 8)).cashiers().get(0);
    assertThat(row.name()).isEqualTo("?");
    assertThat(row.salesPaise()).isZero();
    assertThat(row.refundPercentOfSales()).as("no sales, no percentage").isZero();
  }

  @Test
  void badPeriodsAreRefused() {
    assertThatThrownBy(() -> service.report("year", null, null)).isInstanceOf(ApiException.class);
  }

  @Test
  void percentagesRoundToOneDecimal() {
    assertThat(CounterControlsService.percent(1, 3)).isEqualTo(33.3);
    assertThat(CounterControlsService.percent(5, 0)).isZero();
  }
}
