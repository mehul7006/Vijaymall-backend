package com.vijay.mall.pos.app;

import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.infra.CreditRepository;
import com.vijay.mall.pos.infra.InvoiceCounterRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CreditServiceTest {
  private static final String MOBILE = "9000000001";

  private final UUID cashier = UUID.randomUUID();
  private final UUID customer = UUID.randomUUID();
  private final UUID saleId = UUID.randomUUID();

  private CreditRepository repo;
  private UserRepository users;
  private InvoiceCounterRepository counters;
  private AuditService audit;
  private CreditService service;

  @BeforeEach
  void setUp() {
    repo = mock(CreditRepository.class);
    users = mock(UserRepository.class);
    counters = mock(InvoiceCounterRepository.class);
    audit = mock(AuditService.class);
    ShopService shop = mock(ShopService.class);
    when(shop.get()).thenReturn(PosTestData.shop());
    service = new CreditService(repo, users, counters, shop, audit,
        new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC));
    when(users.findById(customer)).thenReturn(Optional.of(new User(customer, "c@x", "Asha", MOBILE, User.CUSTOMER)));
    when(users.findByMobile(MOBILE)).thenReturn(Optional.of(new User(customer, "c@x", "Asha", MOBILE, User.CUSTOMER)));
    when(repo.find(customer)).thenReturn(new CreditRepository.Account(customer, 3000, 10000));
    when(repo.lock(eq(customer), any())).thenReturn(new CreditRepository.Account(customer, 3000, 10000));
    when(repo.findRepaymentByIdem(anyString())).thenReturn(Optional.empty());
  }

  // ----- reading -----
  @Test
  void accountShowsBalanceLimitAndWhatIsLeft() {
    var a = service.account(customer);
    assertThat(a.name()).isEqualTo("Asha");
    assertThat(a.balancePaise()).isEqualTo(3000);
    assertThat(a.limitPaise()).isEqualTo(10000);
    assertThat(a.availablePaise()).isEqualTo(7000);
  }

  @Test
  void accountByMobileAcceptsOtherWaysOfWritingTheNumber() {
    assertThat(service.accountByMobile("+91 90000 00001").customerId()).isEqualTo(customer);
  }

  @Test
  void unknownOrInvalidCustomersAreRejected() {
    assertThatThrownBy(() -> service.account(UUID.randomUUID())).isInstanceOf(ApiException.class);
    when(users.findByMobile("9000000002")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.accountByMobile("9000000002")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.accountByMobile("123")).isInstanceOf(ApiException.class);
  }

  @Test
  void ledgerIsMappedAndPagedWithinLimits() {
    var e = new CreditRepository.Entry(UUID.randomUUID(), "SALE", 5000, 8000, "VSM/1", saleId, null, null, "", cashier, NOW);
    when(repo.ledger(customer, 0, 20)).thenReturn(new Page<>(List.of(e), 0, 20, 1));
    var page = service.ledger(customer, -2, 20);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).kind()).isEqualTo("SALE");
    assertThat(page.items().get(0).balanceAfterPaise()).isEqualTo(8000);
    assertThat(page.items().get(0).refNo()).isEqualTo("VSM/1");
  }

  // ----- credit on a bill -----
  @Test
  void aBillWithinTheLimitIsPutOnTheAccount() {
    service.chargeSale(customer, 7000, saleId, "VSM/1", cashier, NOW);
    verify(repo).append(eq(customer), eq("SALE"), eq(7000L), eq("VSM/1"), eq(saleId), eq(null), eq(null), eq(""), eq(cashier), eq(NOW));
  }

  @Test
  void aBillOverTheLimitIsRefusedWithTheNumbers() {
    assertThatThrownBy(() -> service.chargeSale(customer, 7001, saleId, "VSM/1", cashier, NOW))
        .isInstanceOfSatisfying(ApiException.class, ex -> {
          assertThat(ex.code()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
          assertThat(ex.details()).containsEntry("availablePaise", 7000L).containsEntry("requestedPaise", 7001L);
        });
    verify(repo, never()).append(any(), anyString(), anyLong(), any(), any(), any(), any(), anyString(), any(), any());
  }

  @Test
  void customersWithoutALimitOrBlockedCannotBuyOnCredit() {
    when(repo.lock(eq(customer), any())).thenReturn(new CreditRepository.Account(customer, 0, 0));
    assertThatThrownBy(() -> service.chargeSale(customer, 100, saleId, "VSM/1", cashier, NOW))
        .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CREDIT_NOT_ALLOWED"));
    when(repo.lock(eq(customer), any())).thenReturn(new CreditRepository.Account(customer, 0, 10000));
    when(users.isBlocked(customer)).thenReturn(true);
    assertThatThrownBy(() -> service.chargeSale(customer, 100, saleId, "VSM/1", cashier, NOW))
        .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CREDIT_NOT_ALLOWED"));
  }

  // ----- goods coming back -----
  @Test
  void returnAdjustmentUsesTheBalanceAndWhatEarlierReturnsAlreadyCancelled() {
    when(repo.returnedAgainst(saleId)).thenReturn(1000L);
    assertThat(service.returnAdjustment(customer, saleId, 2500, 2250, NOW)).isEqualTo(1500); // only 1500 of the bill is still open
  }

  @Test
  void recordReturnLowersTheBalance() {
    UUID returnId = UUID.randomUUID();
    service.recordReturn(customer, saleId, returnId, "VSM-CN/1", 1500, cashier, NOW);
    verify(repo).append(customer, "RETURN", -1500L, "VSM-CN/1", saleId, returnId, null, "", cashier, NOW);
  }

  // ----- repayment -----
  private CreditRepository.Repayment stored(UUID id, long amount) {
    return new CreditRepository.Repayment(id, "VSM-CR/2026-27/000005", customer, cashier, amount, "", NOW,
        List.of(new CreditRepository.Line("CASH", amount, null)));
  }

  @Test
  void repaymentIsSplitOverModesRecordedAndNumbered() {
    UUID id = UUID.randomUUID();
    when(counters.next("CREDIT_RECEIPT", "2026-27")).thenReturn(5L);
    when(repo.insertRepayment(any(), anyString(), anyString(), any(), any(), anyLong(), anyString(), any(), anyList())).thenReturn(id);
    when(repo.findRepayment(id)).thenReturn(Optional.of(stored(id, 2500)));

    var receipt = service.collect(cashier, "k1", "9000000001", List.of(new Pay("CASH", 1500, null), new Pay("UPI", 1000, "u1")), " part ");

    assertThat(receipt.receiptNo()).isEqualTo("VSM-CR/2026-27/000005");
    assertThat(receipt.customerMobile()).isEqualTo(MOBILE);
    verify(repo).insertRepayment(any(UUID.class), eq("VSM-CR/2026-27/000005"), eq("k1"), eq(customer), eq(cashier), eq(2500L),
        eq("part"), eq(NOW), anyList());
    verify(repo).append(eq(customer), eq("PAYMENT"), eq(-2500L), eq("VSM-CR/2026-27/000005"), eq(null), eq(null), any(UUID.class),
        eq("part"), eq(cashier), eq(NOW));
    verify(audit).log(eq(cashier), eq("CREDIT_PAYMENT"), eq("credit_payment"), any(), anyString());
  }

  @Test
  void aRepeatedKeyReturnsTheFirstReceiptAndChangesNothing() {
    UUID id = UUID.randomUUID();
    when(repo.findRepaymentByIdem("k2")).thenReturn(Optional.of(stored(id, 1000)));
    assertThat(service.collect(cashier, "k2", MOBILE, List.of(new Pay("CASH", 1000, null)), null).id()).isEqualTo(id);
    verify(repo, never()).lock(any(), any());
  }

  @Test
  void aRaceOnTheSameKeyReturnsTheWinnersReceipt() {
    UUID id = UUID.randomUUID();
    when(repo.lock(eq(customer), any())).thenThrow(new DataIntegrityViolationException("dup"));
    when(repo.findRepaymentByIdem("k3")).thenReturn(Optional.empty(), Optional.of(stored(id, 1000)));
    assertThat(service.collect(cashier, "k3", MOBILE, List.of(new Pay("CASH", 1000, null)), null).id()).isEqualTo(id);
  }

  @Test
  void repaymentIsValidated() {
    List<Pay> cash = List.of(new Pay("CASH", 1000, null));
    assertThatThrownBy(() -> service.collect(cashier, " ", MOBILE, cash, null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.collect(cashier, null, MOBILE, cash, null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.collect(cashier, "k", MOBILE, List.of(), null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.collect(cashier, "k", MOBILE, null, null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.collect(cashier, "k", MOBILE, List.of(new Pay("CREDIT", 1000, null)), null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.collect(cashier, "k", MOBILE, List.of(new Pay("CASH", 0, null)), null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.collect(cashier, "k", MOBILE, List.of(new Pay("CASH", 100, null)), "x".repeat(201))).isInstanceOf(ApiException.class);
    when(users.findByMobile("9000000009")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.collect(cashier, "k", "9000000009", cash, null)).isInstanceOf(ApiException.class);
    verify(repo, never()).insertRepayment(any(), any(), any(), any(), any(), anyLong(), any(), any(), any());
  }

  @Test
  void paymentLargerThanTheBalanceIsRefused() {
    assertThatThrownBy(() -> service.collect(cashier, "k", MOBILE, List.of(new Pay("CASH", 3001, null)), null))
        .isInstanceOfSatisfying(ApiException.class, ex -> {
          assertThat(ex.code()).isEqualTo("CREDIT_OVERPAYMENT");
          assertThat(ex.details()).containsEntry("balancePaise", 3000L);
        });
    verify(repo, never()).insertRepayment(any(), any(), any(), any(), any(), anyLong(), any(), any(), any());
  }

  // ----- admin -----
  @Test
  void limitCanBeSetAndSwitchedOff() {
    service.setLimit(customer, 50000, cashier);
    verify(repo).setLimit(eq(customer), eq(50000L), any());
    verify(audit).log(eq(cashier), eq("CREDIT_LIMIT"), eq("credit_account"), eq(customer), anyString());
    assertThatThrownBy(() -> service.setLimit(customer, -1, cashier)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.setLimit(UUID.randomUUID(), 100, cashier)).isInstanceOf(ApiException.class);
  }

  @Test
  void adjustmentNeedsAReasonAndCannotTakeTheBalanceBelowZero() {
    service.adjust(customer, -3000, " write-off ", cashier);
    verify(repo).append(eq(customer), eq("ADJUST"), eq(-3000L), eq(null), eq(null), eq(null), eq(null), eq("write-off"), eq(cashier), any());
    verify(audit).log(eq(cashier), eq("CREDIT_ADJUST"), eq("credit_account"), eq(customer), anyString());
    service.adjust(customer, 500, "added by mistake removed", cashier);

    assertThatThrownBy(() -> service.adjust(customer, -3001, "too much", cashier))
        .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CREDIT_ADJUST_TOO_LARGE"));
    assertThatThrownBy(() -> service.adjust(customer, 0, "zero", cashier)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.adjust(customer, 100, " ", cashier)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.adjust(customer, 100, null, cashier)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.adjust(UUID.randomUUID(), 100, "x", cashier)).isInstanceOf(ApiException.class);
  }

  @Test
  void creditCanBeApprovedByMobileEvenForANewCustomer() {
    UUID fresh = UUID.randomUUID();
    when(users.findByMobile("9000000010")).thenReturn(Optional.empty());
    when(users.insertCounterCustomer(eq("9000000010"), any())).thenReturn(new User(fresh, "pos-x@counter.invalid", null, "9000000010", User.CUSTOMER));
    when(users.findById(fresh)).thenReturn(Optional.of(new User(fresh, "pos-x@counter.invalid", null, "9000000010", User.CUSTOMER)));
    when(repo.find(fresh)).thenReturn(new CreditRepository.Account(fresh, 0, 20000));
    service.setLimitByMobile("+91 9000000010", 20000, cashier);
    verify(repo).setLimit(eq(fresh), eq(20000L), any());

    service.setLimitByMobile(MOBILE, 100, cashier);
    verify(users, Mockito.times(1)).insertCounterCustomer(anyString(), any());
    assertThatThrownBy(() -> service.setLimitByMobile(MOBILE, -5, cashier)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.setLimitByMobile("12", 5, cashier)).isInstanceOf(ApiException.class);
  }
}
