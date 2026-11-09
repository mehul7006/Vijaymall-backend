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
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Repayment payment modes (null safe, normalised) and the repayment receipt text. */
class CreditServiceModeAndReceiptTest {
  private static final String MOBILE = "9000000001";
  private final UUID cashier = UUID.randomUUID();
  private final UUID customer = UUID.randomUUID();
  private final UUID payment = UUID.randomUUID();
  private CreditRepository repo;
  private UserRepository users;
  private CreditService service;

  @BeforeEach
  void setUp() {
    repo = mock(CreditRepository.class);
    users = mock(UserRepository.class);
    InvoiceCounterRepository counters = mock(InvoiceCounterRepository.class);
    ShopService shop = mock(ShopService.class);
    when(shop.get()).thenReturn(PosTestData.shop());
    service = new CreditService(repo, users, counters, shop, mock(AuditService.class),
        new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC));
    when(users.findById(customer)).thenReturn(Optional.of(new User(customer, "c@x", "Asha", MOBILE, User.CUSTOMER)));
    when(users.findByMobile(MOBILE)).thenReturn(Optional.of(new User(customer, "c@x", "Asha", MOBILE, User.CUSTOMER)));
    when(users.findById(cashier)).thenReturn(Optional.of(new User(cashier, "k@x", "Kiran", "9000000002", User.CUSTOMER)));
    when(repo.find(customer)).thenReturn(new CreditRepository.Account(customer, 2000, 10000));
    when(repo.lock(eq(customer), any())).thenReturn(new CreditRepository.Account(customer, 5000, 10000));
    when(repo.findRepaymentByIdem(anyString())).thenReturn(Optional.empty());
    when(counters.next("CREDIT_RECEIPT", "2026-27")).thenReturn(5L);
    when(repo.insertRepayment(any(), anyString(), anyString(), any(), any(), anyLong(), anyString(), any(), anyList())).thenReturn(payment);
    when(repo.findRepayment(payment)).thenReturn(Optional.of(new CreditRepository.Repayment(payment, "VSM-CR/2026-27/000005", customer,
        cashier, 3000, "part", NOW, List.of(new CreditRepository.Line("CASH", 1000, null), new CreditRepository.Line("UPI", 2000, "u")))));
    when(repo.entryOfPayment(payment)).thenReturn(Optional.of(new CreditRepository.Entry(UUID.randomUUID(), "PAYMENT", -3000, 2000,
        "VSM-CR/2026-27/000005", null, null, payment, "part", cashier, NOW)));
  }

  @Test void aNullModeIsA400NamingTheLine_notAnNpe() {
    assertThatThrownBy(() -> service.collect(cashier, "k", MOBILE, List.of(new Pay("CASH", 100, null), new Pay(null, 100, null)), null))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
          assertThat(PayModesTest.fields(e)).containsEntry("payments[1].mode", "payment mode must be CASH, UPI or CARD (got none)");
        });
    verify(repo, never()).insertRepayment(any(), any(), any(), any(), any(), anyLong(), any(), any(), any());
  }

  @Test void aBadModeQuotesTheValue_andCreditIsNotARepaymentMode() {
    assertThatThrownBy(() -> service.collect(cashier, "k", MOBILE, List.of(new Pay("credit", 100, null)), null))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(PayModesTest.fields(e))
            .containsEntry("payments[0].mode", "payment mode must be CASH, UPI or CARD (got \"credit\")"));
  }

  @Test void modesAreTrimmedAndUpperCasedBeforeStoring() {
    service.collect(cashier, "k", MOBILE, List.of(new Pay(" cash ", 1000, null), new Pay("upi", 500, "u")), null);
    @SuppressWarnings("unchecked") ArgumentCaptor<List<CreditRepository.Line>> c = ArgumentCaptor.forClass(List.class);
    verify(repo).insertRepayment(any(), anyString(), eq("k"), any(), any(), eq(1500L), anyString(), any(), c.capture());
    assertThat(c.getValue()).extracting(CreditRepository.Line::mode).containsExactly("CASH", "UPI");
  }

  @Test void receiptTextShowsCustomerAmountModesAndTheBalanceBeforeAndAfter() {
    String t = service.receiptText(payment, 32).strip();
    assertThat(t).contains("CREDIT PAYMENT RECEIPT", "VSM-CR/2026-27/000005", "Cashier: Kiran", "Customer: 9000000001", "Balance before",
        "50.00", "Paid CASH", "10.00", "Paid UPI", "20.00", "Amount paid", "30.00", "Balance after", "20.00", "Note: part");
  }

  @Test void receiptTextFallsBackToTodaysBalanceAndTheEmailWhenNothingElseIsKnown() {
    when(repo.entryOfPayment(payment)).thenReturn(Optional.empty());
    when(users.findById(cashier)).thenReturn(Optional.of(new User(cashier, "k@x", " ", "9000000002", User.CUSTOMER)));
    String t = service.receiptText(payment, 48);
    assertThat(t).contains("Cashier: k@x").contains("Balance after");
    when(users.findById(cashier)).thenReturn(Optional.of(new User(cashier, "k@x", null, "9000000002", User.CUSTOMER)));
    assertThat(service.receiptText(payment, 48)).contains("Cashier: k@x");
    when(users.findById(cashier)).thenReturn(Optional.empty());
    assertThat(service.receiptText(payment, 48)).contains("Cashier: \n");
  }

  @Test void receiptTextRejectsAnUnknownPaymentOrCustomerOrWidth() {
    UUID other = UUID.randomUUID();
    when(repo.findRepayment(other)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.receiptText(other, 32)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    assertThatThrownBy(() -> service.receiptText(payment, 40)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    when(users.findById(customer)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.receiptText(payment, 32)).isInstanceOf(ApiException.class);
  }
}
