package com.vijay.mall.pos.app;

import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.WeighedMode;
import com.vijay.mall.pos.infra.PosReportRepository;
import com.vijay.mall.pos.infra.ShopSettingRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ShopStaffReportServiceTest {
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID admin = UUID.randomUUID();

  // ----- shop -----
  private static ShopSetting shop(String name, String gstin, String state, String upi, String prefix, String weighed) {
    return new ShopSetting(name, "addr", "98", gstin, state, upi, prefix, "bye", weighed, WeighedMode.WEIGHT_GRAMS);
  }

  @Test void shop_saveValid_andRead() {
    var repo = mock(ShopSettingRepository.class);
    var service = new ShopService(repo, mock(AuditService.class), clock);
    var s = shop("Vijay", "24ABCDE1234F1Z5", "24", "shop@upi", "VSM", "2");
    when(repo.get()).thenReturn(s);
    assertThat(service.save(s, admin)).isEqualTo(s);
    verify(repo).save(s, NOW);
    assertThat(service.get()).isEqualTo(s);
    service.save(shop("Vijay", "", "24", "", "VSM", "20"), admin); // gstin and upi are optional
  }

  @Test void shop_saveInvalid_rejected() {
    var repo = mock(ShopSettingRepository.class);
    var service = new ShopService(repo, mock(AuditService.class), clock);
    assertThatThrownBy(() -> service.save(shop(" ", "", "24", "", "VSM", "2"), admin)).hasMessageContaining("shop name");
    assertThatThrownBy(() -> service.save(shop("V", "BADGSTIN", "24", "", "VSM", "2"), admin)).hasMessageContaining("GSTIN");
    assertThatThrownBy(() -> service.save(shop("V", "", "2", "", "VSM", "2"), admin)).hasMessageContaining("state");
    assertThatThrownBy(() -> service.save(shop("V", "", "24", "nope", "VSM", "2"), admin)).hasMessageContaining("UPI");
    assertThatThrownBy(() -> service.save(shop("V", "", "24", "", "v", "2"), admin)).hasMessageContaining("capital");
    assertThatThrownBy(() -> service.save(shop("V", "", "24", "", "VSM", "abc"), admin)).hasMessageContaining("digits");
    verify(repo, never()).save(any(), any());
  }

  // ----- staff -----
  @Test void staff_createNew_andPromoteExisting() {
    var users = mock(UserRepository.class);
    var service = new StaffService(users, mock(AuditService.class), clock);
    UUID id = UUID.randomUUID();
    when(users.findByEmail("asha@shop.in")).thenReturn(Optional.empty());
    when(users.upsertStaff("asha@shop.in", "Asha", null, "CASHIER", NOW)).thenReturn(new User(id, "asha@shop.in", "Asha", null, "CASHIER"));
    assertThat(service.create("  Asha@Shop.in ", "Asha", null, admin).id()).isEqualTo(id);
    verify(users).setStatus(id, "ACTIVE");
  }

  @Test void staff_rejectsBadEmail_andAdmins() {
    var users = mock(UserRepository.class);
    var service = new StaffService(users, mock(AuditService.class), clock);
    assertThatThrownBy(() -> service.create("nope", null, null, admin)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.create(null, null, null, admin)).isInstanceOf(ApiException.class);
    when(users.findByEmail("boss@shop.in")).thenReturn(Optional.of(new User(UUID.randomUUID(), "boss@shop.in", null, null, "ADMIN")));
    assertThatThrownBy(() -> service.create("boss@shop.in", null, null, admin)).hasMessageContaining("admin");
    verify(users, never()).upsertStaff(any(), any(), any(), any(), any());
  }

  @Test void staff_listAndBlock() {
    var users = mock(UserRepository.class);
    var service = new StaffService(users, mock(AuditService.class), clock);
    UUID id = UUID.randomUUID();
    User cashier = new User(id, "c@shop.in", null, null, "CASHIER");
    when(users.byRole("CASHIER")).thenReturn(List.of(cashier));
    assertThat(service.list()).containsExactly(cashier);
    when(users.findById(id)).thenReturn(Optional.of(cashier));
    service.setActive(id, false, admin);
    verify(users).setStatus(id, "BLOCKED");
    service.setActive(id, true, admin);
    verify(users).setStatus(id, "ACTIVE");
    UUID customer = UUID.randomUUID();
    when(users.findById(customer)).thenReturn(Optional.of(new User(customer, "x@y.z", null, null, "CUSTOMER")));
    assertThatThrownBy(() -> service.setActive(customer, false, admin)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.setActive(UUID.randomUUID(), false, admin)).isInstanceOf(ApiException.class);
  }

  // ----- report -----
  @Test void report_dayTotals_fillAllModes_andCashInDrawer() {
    var repo = mock(PosReportRepository.class);
    var service = new PosReportService(repo, clock);
    when(repo.sales(any(), any(), eq(null))).thenReturn(new PosReportRepository.Totals(3, 30000, 26000, 2000, 2000, 10));
    when(repo.returns(any(), any(), eq(null))).thenReturn(new PosReportRepository.ReturnTotals(1, 5000));
    when(repo.paidByMode(any(), any(), eq(null))).thenReturn(Map.of("CASH", 20000L, "UPI", 10000L));
    when(repo.refundedByMode(any(), any(), eq(null))).thenReturn(Map.of("CASH", 5000L));
    var r = service.day(LocalDate.of(2026, 10, 27), null);
    assertThat(r.bills()).isEqualTo(3);
    assertThat(r.netPaise()).isEqualTo(25000);
    assertThat(r.paidByMode()).containsEntry("CARD", 0L).containsEntry("CASH", 20000L);
    assertThat(r.refundedByMode()).containsEntry("UPI", 0L);
    assertThat(r.cashExpectedPaise()).isEqualTo(15000);
    assertThat(r.coinsRedeemed()).isEqualTo(10);
  }

  @Test void report_defaultsToToday_andScopesToCashier() {
    var repo = mock(PosReportRepository.class);
    var service = new PosReportService(repo, clock);
    UUID cashier = UUID.randomUUID();
    when(repo.sales(any(), any(), eq(cashier))).thenReturn(new PosReportRepository.Totals(0, 0, 0, 0, 0, 0));
    when(repo.returns(any(), any(), eq(cashier))).thenReturn(new PosReportRepository.ReturnTotals(0, 0));
    when(repo.paidByMode(any(), any(), eq(cashier))).thenReturn(Map.of());
    when(repo.refundedByMode(any(), any(), eq(cashier))).thenReturn(Map.of());
    var r = service.day(null, cashier);
    assertThat(r.date()).isEqualTo(LocalDate.of(2026, 10, 27));
    assertThat(r.cashExpectedPaise()).isZero();
  }

  @Test
  void aBlockedCashierIsNotActive() {
    var users = mock(UserRepository.class);
    var service = new StaffService(users, mock(AuditService.class), clock);
    UUID blocked = UUID.randomUUID();
    Mockito.when(users.isBlocked(blocked)).thenReturn(true);
    Assertions.assertFalse(service.isActive(blocked));
    Assertions.assertTrue(service.isActive(UUID.randomUUID()));
  }

  @Test
  void aShopSettingBuiltTheOldWayHasNoPromise() {
    assertThat(shop("Vijay", "24ABCDE1234F1Z5", "24", "u@upi", "VSM", "2").promise()).isEmpty();
  }

  @Test void report_creditLines_andRepaymentsInCashGoIntoTheDrawer() {
    var repo = mock(PosReportRepository.class);
    var service = new PosReportService(repo, clock);
    when(repo.sales(any(), any(), eq(null))).thenReturn(new PosReportRepository.Totals(4, 40000, 34000, 3000, 3000, 0));
    when(repo.returns(any(), any(), eq(null))).thenReturn(new PosReportRepository.ReturnTotals(1, 2000));
    when(repo.paidByMode(any(), any(), eq(null))).thenReturn(Map.of("CASH", 20000L, "UPI", 5000L, "CREDIT", 15000L));
    when(repo.refundedByMode(any(), any(), eq(null))).thenReturn(Map.of("CASH", 500L));
    when(repo.creditReturned(any(), any(), eq(null))).thenReturn(1500L);
    when(repo.creditRecoveredByMode(any(), any(), eq(null))).thenReturn(Map.of("CASH", 3000L, "UPI", 1000L));
    var r = service.day(LocalDate.of(2026, 10, 27), null);
    assertThat(r.creditGivenPaise()).isEqualTo(15000);
    assertThat(r.creditReturnedPaise()).isEqualTo(1500);
    assertThat(r.creditRecoveredPaise()).isEqualTo(4000);
    assertThat(r.creditRecoveredByMode()).containsEntry("CASH", 3000L).containsEntry("UPI", 1000L).containsEntry("CARD", 0L);
    assertThat(r.paidByMode()).doesNotContainKey("CREDIT"); // credit is owed, not money received
    assertThat(r.cashExpectedPaise()).isEqualTo(22500); // 20000 + 3000 repaid in cash - 500 refunded in cash
    assertThat(r.salesPaise()).isEqualTo(40000);
  }
}
