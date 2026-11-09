package com.vijay.mall.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.live.WalletChanged;
import com.vijay.mall.wallet.infra.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WalletServiceCoverageTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private WalletRepository repo;
  private ApplicationEventPublisher events;
  private WalletService service;
  private final UUID user = UUID.randomUUID();
  private final UUID order = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(WalletRepository.class);
    events = mock(ApplicationEventPublisher.class);
    service = new WalletService(repo, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC),
        new TransactionTemplate(mock(PlatformTransactionManager.class)), events);
    when(repo.insertLedger(any(), any(), anyString(), anyInt(), any(), any(), any(), any(), anyString(), any())).thenReturn(true);
  }

  @Test void reverse_lotWithoutExpiry_goesBackToTheLot_andPublishesEvent() {
    UUID redeem = UUID.randomUUID(), lot = UUID.randomUUID();
    when(repo.ledgerIdByKey("redeem:" + order)).thenReturn(java.util.Optional.of(redeem));
    when(repo.lotUses(redeem)).thenReturn(List.of(new WalletRepository.LotUse(lot, 7, null)));
    service.reverse(user, order);
    verify(repo).changeLot(lot, 7);
    verify(repo).addBalance(user, 7);
    ArgumentCaptor<WalletChanged> ev = ArgumentCaptor.forClass(WalletChanged.class);
    verify(events).publishEvent(ev.capture());
    assertThat(ev.getValue().reason()).isEqualTo("REVERSE");
    assertThat(ev.getValue().delta()).isEqualTo(7);
    assertThat(ev.getValue().userId()).isEqualTo(user);
  }

  @Test void reverse_redeemWithNoLotUses_changesNothing() {
    UUID redeem = UUID.randomUUID();
    when(repo.ledgerIdByKey("redeem:" + order)).thenReturn(java.util.Optional.of(redeem));
    when(repo.lotUses(redeem)).thenReturn(List.of());
    service.reverse(user, order);
    verify(repo, never()).insertLedger(any(), any(), eq("REVERSE"), anyInt(), any(), any(), any(), any(), anyString(), any());
    verify(repo, never()).addBalance(any(), anyInt());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test void expireLots_emptyLotIsSkipped() {
    UUID lot = UUID.randomUUID();
    when(repo.expiredLots(any(), anyInt())).thenReturn(List.of(new WalletRepository.ExpiredLot(lot, user, 5)));
    when(repo.remaining(lot)).thenReturn(0);
    service.expireLots();
    verify(repo).lockBalance(user);
    verify(repo, never()).zeroLot(any());
    verify(repo, never()).addBalance(any(), anyInt());
  }

  @Test void expireLots_alreadyExpiredRowIsLeftAlone() {
    UUID lot = UUID.randomUUID();
    when(repo.expiredLots(any(), anyInt())).thenReturn(List.of(new WalletRepository.ExpiredLot(lot, user, 5)));
    when(repo.remaining(lot)).thenReturn(5);
    when(repo.insertLedger(any(), eq(user), eq("EXPIRE"), eq(-5), any(), any(), any(), any(), eq("expire:" + lot), any()))
        .thenReturn(false);
    service.expireLots();
    verify(repo, never()).zeroLot(lot);
    verify(repo, never()).addBalance(any(), anyInt());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test void expireLots_nothingLeftInBalance_takesNothingAndSendsNoEvent() {
    UUID lot = UUID.randomUUID();
    when(repo.expiredLots(any(), anyInt())).thenReturn(List.of(new WalletRepository.ExpiredLot(lot, user, 5)));
    when(repo.remaining(lot)).thenReturn(5);
    when(repo.balance(user)).thenReturn(0);
    service.expireLots();
    verify(repo).zeroLot(lot);
    verify(repo).addBalance(user, 0);
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test void expireLots_partlySpent_takesWhatTheBalanceHolds_andPublishes() {
    UUID lot = UUID.randomUUID();
    when(repo.expiredLots(any(), anyInt())).thenReturn(List.of(new WalletRepository.ExpiredLot(lot, user, 5)));
    when(repo.remaining(lot)).thenReturn(5);
    when(repo.balance(user)).thenReturn(3);
    service.expireLots();
    verify(repo).addBalance(user, -3);
    ArgumentCaptor<WalletChanged> ev = ArgumentCaptor.forClass(WalletChanged.class);
    verify(events).publishEvent(ev.capture());
    assertThat(ev.getValue().delta()).isEqualTo(-3);
    assertThat(ev.getValue().reason()).isEqualTo("EXPIRE");
  }
}
