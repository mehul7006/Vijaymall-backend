package com.vijay.mall.wallet.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.wallet.domain.CoinMode;
import com.vijay.mall.wallet.domain.CoinRule;
import com.vijay.mall.wallet.domain.CoinSettings;
import com.vijay.mall.wallet.domain.LotAllocator;
import com.vijay.mall.wallet.domain.StoredRule;
import com.vijay.mall.wallet.infra.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WalletServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private WalletRepository repo;
  private WalletService service;
  private final UUID user = UUID.randomUUID();
  private final UUID order = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(WalletRepository.class);
    service = new WalletService(repo, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC),
        new TransactionTemplate(mock(PlatformTransactionManager.class)));
    when(repo.settings()).thenReturn(new CoinSettings(100, 50, 12));
    when(repo.insertLedger(any(), any(), anyString(), anyInt(), any(), any(), any(), any(), anyString(), any())).thenReturn(true);
  }

  @Test
  void redeemSpendsTheSoonestExpiringCoinsFirst() {
    UUID soon = UUID.randomUUID(), later = UUID.randomUUID();
    when(repo.lockBalance(user)).thenReturn(100);
    when(repo.openLotsForUpdate(eq(user), any())).thenReturn(List.of(
        new LotAllocator.Lot(later, 80, NOW.plusSeconds(1000)), new LotAllocator.Lot(soon, 30, NOW.plusSeconds(10))));
    service.redeem(user, 50, order);
    verify(repo).changeLot(soon, -30);
    verify(repo).changeLot(later, -20);
    verify(repo).addBalance(user, -50);
    verify(repo).insertLedger(any(), eq(user), eq("REDEEM"), eq(-50), eq(null), eq(null), eq(order), eq(null), eq("redeem:" + order), any());
  }

  @Test
  void redeemMoreThanTheBalanceIsRejected() {
    when(repo.lockBalance(user)).thenReturn(10);
    ApiException e = assertThrows(ApiException.class, () -> service.redeem(user, 11, order));
    assertEquals("VALIDATION_FAILED", e.code());
    verify(repo, never()).addBalance(any(), anyInt());
  }

  @Test
  void redeemZeroDoesNothing() {
    service.redeem(user, 0, order);
    verify(repo, never()).lockBalance(any());
  }

  @Test
  void redeemingTheSameOrderTwiceChangesNothingTheSecondTime() {
    when(repo.lockBalance(user)).thenReturn(100);
    when(repo.openLotsForUpdate(eq(user), any())).thenReturn(List.of(new LotAllocator.Lot(UUID.randomUUID(), 100, null)));
    when(repo.insertLedger(any(), any(), eq("REDEEM"), anyInt(), any(), any(), any(), any(), anyString(), any())).thenReturn(false);
    service.redeem(user, 10, order);
    verify(repo, never()).addBalance(any(), anyInt());
    verify(repo, never()).changeLot(any(), anyInt());
  }

  @Test
  void creditAddsALotThatExpiresInTheConfiguredMonths() {
    service.credit(user, 25, order);
    ArgumentCaptor<Instant> expires = ArgumentCaptor.forClass(Instant.class);
    verify(repo).insertLedger(any(), eq(user), eq("EARN"), eq(25), eq(25), expires.capture(), eq(order), any(), eq("earn:" + order), any());
    assertEquals(Instant.parse("2027-11-01T10:00:00Z"), expires.getValue());
    verify(repo).addBalance(user, 25);
  }

  @Test
  void creditTwiceForTheSameOrderOnlyCountsOnce() {
    when(repo.insertLedger(any(), any(), eq("EARN"), anyInt(), any(), any(), any(), any(), anyString(), any())).thenReturn(false);
    service.credit(user, 25, order);
    verify(repo, never()).addBalance(any(), anyInt());
  }

  @Test
  void reverseReturnsCoinsToTheirLotsAndBalance() {
    UUID redeemId = UUID.randomUUID(), lot = UUID.randomUUID();
    when(repo.ledgerIdByKey("redeem:" + order)).thenReturn(Optional.of(redeemId));
    when(repo.lotUses(redeemId)).thenReturn(List.of(new WalletRepository.LotUse(lot, 30, NOW.plusSeconds(500))));
    service.reverse(user, order);
    verify(repo).changeLot(lot, 30);
    verify(repo).addBalance(user, 30);
    verify(repo).insertLedger(any(), eq(user), eq("REVERSE"), eq(30), any(), any(), eq(order), any(), eq("reverse:" + order), any());
  }

  @Test
  void coinsFromAnExpiredLotComeBackAsAShortLivedLot() {
    UUID redeemId = UUID.randomUUID(), lot = UUID.randomUUID();
    when(repo.ledgerIdByKey("redeem:" + order)).thenReturn(Optional.of(redeemId));
    when(repo.lotUses(redeemId)).thenReturn(List.of(new WalletRepository.LotUse(lot, 30, NOW.minusSeconds(5))));
    service.reverse(user, order);
    verify(repo, never()).changeLot(any(), anyInt());
    verify(repo).insertLedger(any(), eq(user), eq("ADJUST"), eq(0), eq(30), eq(NOW.plusSeconds(30L * 86400)), eq(order), any(), eq("refund-lot:" + order), any());
    verify(repo).addBalance(user, 30);
  }

  @Test
  void reverseWithoutARedeemDoesNothing() {
    when(repo.ledgerIdByKey(anyString())).thenReturn(Optional.empty());
    service.reverse(user, order);
    verify(repo, never()).addBalance(any(), anyInt());
  }

  @Test
  void expireWritesAnExpireRowAndEmptiesTheLot() {
    UUID lot = UUID.randomUUID();
    when(repo.expiredLots(any(), anyInt())).thenReturn(List.of(new WalletRepository.ExpiredLot(lot, user, 40)));
    when(repo.remaining(lot)).thenReturn(40);
    when(repo.balance(user)).thenReturn(100);
    service.expireLots();
    verify(repo).zeroLot(lot);
    verify(repo).addBalance(user, -40);
  }

  @Test
  void onlyLiveRulesInsideTheirWindowAreActive() {
    CoinRule rule = new CoinRule("r", 100000, 10, CoinMode.PRO_RATA, 0);
    when(repo.rules()).thenReturn(List.of(
        new StoredRule(UUID.randomUUID(), rule, null, null, true),
        new StoredRule(UUID.randomUUID(), rule, NOW.plusSeconds(60), null, true),
        new StoredRule(UUID.randomUUID(), rule, null, NOW.minusSeconds(60), true),
        new StoredRule(UUID.randomUUID(), rule, null, null, false)));
    assertEquals(1, service.activeRules(NOW).size());
  }

  @Test
  void settingsAreValidated() {
    UUID admin = UUID.randomUUID();
    assertThrows(ApiException.class, () -> service.saveSettings(new CoinSettings(0, 50, 12), admin));
    assertThrows(ApiException.class, () -> service.saveSettings(new CoinSettings(100, 101, 12), admin));
    assertThrows(ApiException.class, () -> service.saveSettings(new CoinSettings(100, 50, 0), admin));
    service.saveSettings(new CoinSettings(100, 50, 12), admin);
    verify(repo).saveSettings(new CoinSettings(100, 50, 12));
    assertTrue(true);
  }

  @Test
  void clawbackTakesEarnedCoinsFromOpenLots_soonestFirst() {
    UUID ret = UUID.randomUUID();
    UUID soon = UUID.randomUUID(), later = UUID.randomUUID();
    when(repo.lockBalance(user)).thenReturn(100);
    when(repo.openLotsForUpdate(eq(user), any())).thenReturn(List.of(
        new LotAllocator.Lot(later, 80, NOW.plusSeconds(1000)), new LotAllocator.Lot(soon, 30, NOW.plusSeconds(10))));
    assertEquals(40, service.clawback(user, 40, ret));
    verify(repo).changeLot(soon, -30);
    verify(repo).changeLot(later, -10);
    verify(repo).addBalance(user, -40);
    verify(repo).insertLedger(any(), eq(user), eq("ADJUST"), eq(-40), eq(null), eq(null), eq(ret), eq(null), eq("clawback:" + ret), any());
  }

  @Test
  void clawbackIsCappedAtWhatIsStillUnspent() {
    UUID ret = UUID.randomUUID();
    UUID lot = UUID.randomUUID();
    when(repo.openLotsForUpdate(eq(user), any())).thenReturn(List.of(new LotAllocator.Lot(lot, 5, NOW.plusSeconds(10))));
    assertEquals(5, service.clawback(user, 40, ret));
    verify(repo).addBalance(user, -5);
  }

  @Test
  void clawbackDoesNothingWithoutCoinsOrLots_orWhenAlreadyDone() {
    UUID ret = UUID.randomUUID();
    assertEquals(0, service.clawback(user, 0, ret));
    when(repo.openLotsForUpdate(eq(user), any())).thenReturn(List.of());
    assertEquals(0, service.clawback(user, 10, ret));
    UUID lot = UUID.randomUUID();
    when(repo.openLotsForUpdate(eq(user), any())).thenReturn(List.of(new LotAllocator.Lot(lot, 50, null)));
    when(repo.insertLedger(any(), any(), eq("ADJUST"), anyInt(), any(), any(), any(), any(), anyString(), any())).thenReturn(false);
    assertEquals(0, service.clawback(user, 10, ret));
    verify(repo, never()).addBalance(any(), anyInt());
  }

  @Test
  void restoreAddsAFreshThirtyDayLot_once() {
    UUID ret = UUID.randomUUID();
    service.restore(user, 12, ret);
    verify(repo).insertLedger(any(), eq(user), eq("ADJUST"), eq(12), eq(12), eq(NOW.plusSeconds(30L * 86400)), eq(ret), eq(null),
        eq("restore:" + ret), any());
    verify(repo).addBalance(user, 12);
    service.restore(user, 0, ret);
    when(repo.insertLedger(any(), any(), eq("ADJUST"), anyInt(), any(), any(), any(), any(), eq("restore:" + ret), any())).thenReturn(false);
    service.restore(user, 5, ret);
    verify(repo, never()).addBalance(user, 5);
  }
}
