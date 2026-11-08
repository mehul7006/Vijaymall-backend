package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.infra.PosReportRepository;
import com.vijay.mall.pos.infra.PosShiftRepository;
import com.vijay.mall.pos.infra.PosShiftRepository.Shift;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ShiftServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  private final UUID cashier = UUID.randomUUID();
  private final UUID shiftId = UUID.randomUUID();
  private PosShiftRepository shifts;
  private PosReportRepository report;
  private ShiftService service;
  private final Shift open = new Shift(shiftId, cashier, NOW.minusSeconds(3600), 100000, null, null, null, null, null);

  @BeforeEach
  void setUp() {
    shifts = mock(PosShiftRepository.class);
    report = mock(PosReportRepository.class);
    service = new ShiftService(shifts, report, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    when(shifts.open(cashier)).thenReturn(Optional.of(open));
    when(shifts.find(shiftId)).thenReturn(Optional.of(open));
    // 30000 cash in, 5000 cash refunded, 2000 cash repayments: 100000 + 30000 - 5000 + 2000 = 127000
    when(report.paidByMode(any(), any(), eq(cashier))).thenReturn(Map.of("CASH", 30000L, "UPI", 9999L));
    when(report.refundedByMode(any(), any(), eq(cashier))).thenReturn(Map.of("CASH", 5000L));
    when(report.creditRecoveredByMode(any(), any(), eq(cashier))).thenReturn(Map.of("CASH", 2000L, "UPI", 500L));
  }

  @Test
  void openSharesFloatAndRefusesASecondShiftOrNegativeFloat() {
    UUID other = UUID.randomUUID();
    when(shifts.open(other)).thenReturn(Optional.empty());
    when(shifts.insert(eq(other), eq(5000L), any())).thenReturn(new Shift(UUID.randomUUID(), other, NOW, 5000, null, null, null, null, null));
    var v = service.open(other, 5000);
    assertThat(v.open()).isTrue();
    assertThat(v.openingFloatPaise()).isEqualTo(5000);

    assertThatThrownBy(() -> service.open(cashier, 100)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("SHIFT_ALREADY_OPEN"));
    assertThatThrownBy(() -> service.open(other, -1)).isInstanceOf(ApiException.class);
  }

  @Test
  void twoOpensAtTheSameMomentLeaveOnlyOne() {
    UUID other = UUID.randomUUID();
    when(shifts.open(other)).thenReturn(Optional.empty());
    when(shifts.insert(eq(other), anyLong(), any())).thenThrow(new DataIntegrityViolationException("pos_shift_one_open"));
    assertThatThrownBy(() -> service.open(other, 0)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("SHIFT_ALREADY_OPEN"));
  }

  @Test
  void openShiftShowsLiveExpectedCash() {
    assertThat(service.current(cashier).expectedCashPaise()).isEqualTo(127000);
    UUID none = UUID.randomUUID();
    when(shifts.open(none)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.current(none)).isInstanceOf(ApiException.class);
    assertThat(service.hasOpenShift(cashier)).isTrue();
    assertThat(service.hasOpenShift(none)).isFalse();
  }

  @Test
  void requireOpenRefusesBillingWithoutAShift() {
    UUID none = UUID.randomUUID();
    when(shifts.open(none)).thenReturn(Optional.empty());
    service.requireOpen(cashier);
    assertThatThrownBy(() -> service.requireOpen(none)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("SHIFT_NOT_OPEN"));
  }

  @Test
  void aMatchingCountClosesWithoutANote() {
    service.close(cashier, 127000, null);
    verify(shifts).close(shiftId, NOW, 127000, 127000, "");
  }

  @Test
  void aDifferenceNeedsAReasonAndIsStoredWithIt() {
    assertThatThrownBy(() -> service.close(cashier, 126000, " ")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("VARIANCE_NEEDS_NOTE"));
    verify(shifts, never()).close(any(), any(), anyLong(), anyLong(), any());
    service.close(cashier, 128000, "customer left change");
    verify(shifts).close(shiftId, NOW, 127000, 128000, "customer left change");
  }

  @Test
  void badInputAndMissingShiftAreRefused() {
    assertThatThrownBy(() -> service.close(cashier, -1, "x")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.close(cashier, 1, "x".repeat(201))).isInstanceOf(ApiException.class);
    UUID none = UUID.randomUUID();
    when(shifts.open(none)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.close(none, 0, "")).isInstanceOf(ApiException.class);
  }

  @Test
  void adminListShowsClosedShiftsWithTheirStoredFigures() {
    var closed = new Shift(UUID.randomUUID(), cashier, NOW.minusSeconds(9000), 1000, NOW.minusSeconds(100), 5000L, 4900L, -100L, "short");
    when(shifts.page(null, 0, 20)).thenReturn(new Page<>(List.of(closed), 0, 20, 1));
    var p = service.list(null, 0, 20);
    assertThat(p.items()).singleElement().satisfies(v -> {
      assertThat(v.open()).isFalse();
      assertThat(v.expectedCashPaise()).isEqualTo(5000);
      assertThat(v.variancePaise()).isEqualTo(-100);
    });
  }
}
