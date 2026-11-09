package com.vijay.mall.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.customers.app.CustomerAdminService;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.RefreshTokenRepository;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.order.app.OrderService;
import com.vijay.mall.pos.app.CreditService;
import com.vijay.mall.wallet.app.WalletService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CustomerAdminServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private final UUID actor = UUID.randomUUID();
  private final UUID id = UUID.randomUUID();
  private UserRepository users;
  private WalletService wallet;
  private CreditService credit;
  private OrderService orders;
  private RefreshTokenRepository tokens;
  private AuditService audit;
  private CustomerAdminService service;

  @BeforeEach
  void setUp() {
    users = mock(UserRepository.class);
    wallet = mock(WalletService.class);
    credit = mock(CreditService.class);
    orders = mock(OrderService.class);
    tokens = mock(RefreshTokenRepository.class);
    audit = mock(AuditService.class);
    service = new CustomerAdminService(users, wallet, credit, orders, tokens, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    when(wallet.balance(any())).thenReturn(40);
    when(orders.openOrderCount(any())).thenReturn(2L);
    when(credit.account(any())).thenReturn(new CreditService.AccountView(id, "Asha", "9000000001", 500, 1000, 500));
  }

  private UserRepository.Detail detail(String role, String status) {
    return new UserRepository.Detail(new User(id, "a@x.com", "Asha", "9000000001", role), status, true, "APP", NOW);
  }

  @Test
  void searchMarksBlockedCustomersInactive() {
    var active = detail(User.CUSTOMER, "ACTIVE");
    var blocked = detail(User.CUSTOMER, "BLOCKED");
    when(users.searchCustomers("as", 0, 50)).thenReturn(new Page<>(List.of(active, blocked), 0, 50, 2));
    var out = service.search("as", -4, 50);
    assertThat(out.items()).extracting(CustomerAdminService.CustomerRow::active).containsExactly(true, false);
    assertThat(out.total()).isEqualTo(2);
  }

  @Test
  void getShowsTheFullPictureAndRefusesStaffAndUnknownIds() {
    when(users.detail(id)).thenReturn(Optional.of(detail(User.CUSTOMER, "ACTIVE")));
    var v = service.get(id);
    assertThat(v.coinBalance()).isEqualTo(40);
    assertThat(v.creditBalancePaise()).isEqualTo(500);
    assertThat(v.openOrders()).isEqualTo(2);
    assertThat(v.active()).isTrue();
    when(users.detail(id)).thenReturn(Optional.of(detail(User.ADMIN, "ACTIVE")));
    assertThatThrownBy(() -> service.get(id)).isInstanceOf(ApiException.class);
    when(users.detail(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(id)).isInstanceOf(ApiException.class);
  }

  @Test
  void blockingSignsTheCustomerOutEverywhereAndUnblockingDoesNot() {
    when(users.detail(id)).thenReturn(Optional.of(detail(User.CUSTOMER, "BLOCKED")));
    assertThat(service.setActive(id, false, actor).active()).isFalse();
    verify(users).setStatus(id, "BLOCKED");
    verify(tokens).revokeAll(id, NOW);
    verify(audit).log(actor, "CUSTOMER_BLOCK", "users", id, null);

    Mockito.clearInvocations(tokens);
    service.setActive(id, true, actor);
    verify(users).setStatus(id, "ACTIVE");
    verify(tokens, never()).revokeAll(any(), any());
    verify(audit).log(actor, "CUSTOMER_UNBLOCK", "users", id, null);
  }

  @Test
  void coinAdjustmentsNeedAReasonOfReasonableLength() {
    when(users.detail(id)).thenReturn(Optional.of(detail(User.CUSTOMER, "ACTIVE")));
    assertThatThrownBy(() -> service.adjustCoins(id, 5, null, actor)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.adjustCoins(id, 5, "   ", actor)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.adjustCoins(id, 5, "x".repeat(101), actor)).isInstanceOf(ApiException.class);
    verify(wallet, never()).adminAdjust(any(), anyInt(), any(), anyString());
    service.adjustCoins(id, 5, "  goodwill  ", actor);
    verify(wallet).adminAdjust(id, 5, actor, "goodwill");
    service.adjustCoins(id, -5, "x".repeat(100), actor);
    verify(wallet).adminAdjust(id, -5, actor, "x".repeat(100));
  }

  @Test
  void coinErrorsAreReportedUnderDeltaAndStillUnderCoins() {
    when(users.detail(id)).thenReturn(Optional.of(detail(User.CUSTOMER, "ACTIVE")));
    when(wallet.adminAdjust(id, 0, actor, "x")).thenThrow(ApiException.validation("coins", "a non-zero number"));
    assertThatThrownBy(() -> service.adjustCoins(id, 0, "x", actor)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status().value()).isEqualTo(400);
      assertThat(e.details().get("fields")).isEqualTo(java.util.Map.of("delta", "a non-zero number", "coins", "a non-zero number"));
    });
  }

  @Test
  void otherWalletErrorsPassThroughUnchanged() {
    when(users.detail(id)).thenReturn(Optional.of(detail(User.CUSTOMER, "ACTIVE")));
    ApiException other = ApiException.notFound("Wallet");
    when(wallet.adminAdjust(id, 3, actor, "x")).thenThrow(other);
    assertThatThrownBy(() -> service.adjustCoins(id, 3, "x", actor)).isSameAs(other);
    ApiException otherField = ApiException.validation("amount", "bad");
    when(wallet.adminAdjust(id, 4, actor, "x")).thenThrow(otherField);
    assertThatThrownBy(() -> service.adjustCoins(id, 4, "x", actor)).isSameAs(otherField);
  }

  @Test
  void walletLedgerAndOrdersNeedARealCustomer() {
    when(users.detail(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.walletLedger(id, 0, 20)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.orders(id, 0, 20)).isInstanceOf(ApiException.class);
    verify(wallet, never()).ledger(any(), anyInt(), anyInt());
  }

  @Test
  void walletLedgerDelegatesToTheWallet() {
    when(users.detail(id)).thenReturn(Optional.of(detail(User.CUSTOMER, "ACTIVE")));
    var row = new com.vijay.mall.wallet.infra.WalletRepository.LedgerRow(UUID.randomUUID(), "EARN", 5, null, NOW, NOW);
    when(wallet.ledger(id, 1, 500)).thenReturn(new Page<>(List.of(row), 1, 50, 1));
    assertThat(service.walletLedger(id, 1, 500).items()).containsExactly(row);
  }

  @Test
  void ordersAreShownAsAdminRowsWithTheCustomerDetails() {
    when(users.detail(id)).thenReturn(Optional.of(detail(User.CUSTOMER, "ACTIVE")));
    var s = new com.vijay.mall.order.domain.OrderViews.Summary(UUID.randomUUID(), "VSM-1", "PLACED", "DELIVERY", 900, 2, NOW, 800,
        "C1", 10, 3, 3, 100, "COD", "PENDING");
    when(orders.list(id, 0, 20)).thenReturn(new Page<>(List.of(s), 0, 20, 1));
    var out = service.orders(id, 0, 20);
    assertThat(out.total()).isEqualTo(1);
    var row = out.items().get(0);
    assertThat(row.orderNo()).isEqualTo("VSM-1");
    assertThat(row.customerName()).isEqualTo("Asha");
    assertThat(row.customerEmail()).isEqualTo("a@x.com");
    assertThat(row.customerMobile()).isEqualTo("9000000001");
    assertThat(row.coinsUsed()).isEqualTo(3);
    assertThat(row.paymentStatus()).isEqualTo("PENDING");
  }
}
