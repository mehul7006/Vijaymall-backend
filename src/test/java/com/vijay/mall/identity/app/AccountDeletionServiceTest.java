package com.vijay.mall.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.AddressRepository;
import com.vijay.mall.identity.infra.DeviceRepository;
import com.vijay.mall.identity.infra.RefreshTokenRepository;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.order.app.OrderService;
import com.vijay.mall.pos.app.CreditService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountDeletionServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  private final UUID id = UUID.randomUUID();
  private UserRepository users;
  private AddressRepository addresses;
  private DeviceRepository devices;
  private RefreshTokenRepository tokens;
  private OrderService orders;
  private CreditService credit;
  private AccountDeletionService service;

  @BeforeEach
  void setUp() {
    users = mock(UserRepository.class);
    addresses = mock(AddressRepository.class);
    devices = mock(DeviceRepository.class);
    tokens = mock(RefreshTokenRepository.class);
    orders = mock(OrderService.class);
    credit = mock(CreditService.class);
    service = new AccountDeletionService(users, addresses, devices, tokens, orders, credit, mock(AuditService.class),
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(users.findById(id)).thenReturn(Optional.of(new User(id, "a@b.c", "A", "9000000001", User.CUSTOMER)));
    when(credit.account(id)).thenReturn(new CreditService.AccountView(id, "A", "9000000001", 0, 0, 0, false));
  }

  @Test
  void erasesPersonalDataButKeepsTheRow() {
    service.deleteAccount(id);
    verify(addresses).deleteAll(id);
    verify(devices).deleteAll(id);
    verify(tokens).revokeAll(id, NOW);
    verify(users).anonymize(id, NOW);
  }

  @Test
  void openOrdersBlockDeletion() {
    when(orders.openOrderCount(id)).thenReturn(1L);
    assertThatThrownBy(() -> service.deleteAccount(id)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("ACCOUNT_HAS_OPEN_ORDERS"));
    verify(users, never()).anonymize(any(), any());
  }

  @Test
  void moneyOwedToTheShopBlocksDeletion() {
    when(credit.account(id)).thenReturn(new CreditService.AccountView(id, "A", "9000000001", 5000, 10000, 5000, false));
    assertThatThrownBy(() -> service.deleteAccount(id)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("ACCOUNT_HAS_DUES"));
    verify(users, never()).anonymize(any(), any());
  }

  @Test
  void staffAccountsCannotBeDeletedFromTheCustomerApp() {
    when(users.findById(id)).thenReturn(Optional.of(new User(id, "c@shop.in", "C", null, User.CASHIER)));
    assertThatThrownBy(() -> service.deleteAccount(id)).isInstanceOf(ApiException.class);
    verify(users, never()).anonymize(any(), any());
  }
}
