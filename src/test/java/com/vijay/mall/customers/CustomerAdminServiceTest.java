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

    org.mockito.Mockito.clearInvocations(tokens);
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
}
