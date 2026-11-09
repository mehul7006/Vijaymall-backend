package com.vijay.mall.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.domain.OrderChanged;
import com.vijay.mall.order.domain.OrderStatus;
import com.vijay.mall.order.domain.OrderViews;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.domain.PaymentEvents;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Branches of OrderService the main unit test does not reach: validation, payment results, expiry, list views. */
class OrderServiceCoverageTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private OrderRepository repo;
  private PaymentService payments;
  private ProfileService profiles;
  private WalletService wallet;
  private InventoryService inventory;
  private ApplicationEventPublisher events;
  private OrderService service;

  private final UUID user = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(OrderRepository.class);
    payments = mock(PaymentService.class);
    profiles = mock(ProfileService.class);
    wallet = mock(WalletService.class);
    inventory = mock(InventoryService.class);
    events = mock(ApplicationEventPublisher.class);
    AppProps props = new AppProps(null, null, null, null,
        new AppProps.Payment("fake", 15, null, null, null, null), new AppProps.Delivery(4000, 49900));
    service = new OrderService(repo, mock(QuoteService.class), inventory, wallet, mock(PromotionService.class), payments,
        profiles, mock(OutboxService.class), mock(AuditService.class), JsonMapper.builder().build(),
        new TransactionTemplate(mock(PlatformTransactionManager.class)), props, Clock.fixed(NOW, ZoneOffset.UTC), events);
    when(repo.items(any())).thenReturn(List.of());
    when(repo.timeline(any())).thenReturn(List.of());
    when(payments.sessionFor(any())).thenReturn(Optional.empty());
    when(profiles.get(any())).thenReturn(new User(user, "a@b.com", "Asha", "9999999999", "CUSTOMER"));
    when(repo.updateStatus(any(), anyString(), anyLong(), any())).thenReturn(true);
  }

  private OrderRepository.Row row(UUID id, String status, String method, String payStatus, int coinsEarned, String snapshot) {
    return new OrderRepository.Row(id, "VSM-9", user, "DELIVERY", status, 10000, null, 0, 0, 0, 0, 10000, coinsEarned,
        false, method, payStatus, snapshot, null, NOW, 2, null, null, false);
  }

  private OrderRepository.Row withFulfilment(OrderRepository.Row r, String fulfilment) {
    return new OrderRepository.Row(r.id(), r.orderNo(), r.userId(), fulfilment, r.status(), r.subtotal(), r.couponCode(), r.couponDiscount(),
        r.coinsUsed(), r.coinDiscount(), r.deliveryFee(), r.total(), r.coinsEarned(), r.coinsCredited(), r.paymentMethod(), r.paymentStatus(),
        r.addressSnapshot(), r.expiresAt(), r.placedAt(), r.version(), r.deliverySlot(), r.cancelReason(), r.stockReserved());
  }

  private OrderRepository.Row stub(String status, String method, String payStatus, int coinsEarned) {
    UUID id = UUID.randomUUID();
    OrderRepository.Row r = row(id, status, method, payStatus, coinsEarned, null);
    when(repo.find(id)).thenReturn(Optional.of(r));
    return r;
  }

  @Test
  void placingNeedsANonBlankIdempotencyKey() {
    for (String key : new String[] {null, "", "   "}) {
      assertThatThrownBy(() -> service.place(user, key, null)).isInstanceOf(ApiException.class);
    }
    verify(repo, never()).findByIdem(any(), anyString());
  }

  @Test
  void staffCanCancelAnOrderStillAwaitingPayment() {
    OrderRepository.Row r = stub("PAYMENT_PENDING", "ONLINE", "INITIATED", 0);
    when(payments.refund(any(), anyString())).thenReturn(false);
    service.adminChangeStatus(r.id(), OrderStatus.CANCELLED, "no payment", actor);
    verify(repo).updateStatus(r.id(), "CANCELLED", 2, NOW);
    // nothing was captured, so the attempted payment is closed as failed instead of refunded
    verify(repo).setPaymentStatus(r.id(), "FAILED", NOW);
  }

  @Test
  void staffCannotWaveAnUnpaidOrderThrough() {
    OrderRepository.Row r = stub("PAYMENT_PENDING", "ONLINE", "INITIATED", 0);
    assertThatThrownBy(() -> service.adminChangeStatus(r.id(), OrderStatus.CONFIRMED, null, actor))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AWAITING_PAYMENT"));
    verify(repo, never()).updateStatus(any(), anyString(), anyLong(), any());
  }

  @Test
  void rejectNeedsAReasonOfSaneLength() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> service.adminReject(id, null, actor)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.adminReject(id, "   ", actor)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.adminReject(id, "x".repeat(201), actor)).isInstanceOf(ApiException.class);
    verify(repo, never()).find(any());
  }

  @Test
  void rejectAcceptsAReasonOfExactlyTwoHundredCharacters() {
    OrderRepository.Row r = stub("PLACED", "COD", "NONE", 0);
    String reason = "y".repeat(200);
    service.adminReject(r.id(), "  " + reason + "  ", actor);
    verify(repo).setCancelInfo(r.id(), "SHOP", "REJECTED_BY_SHOP", reason);
  }

  @Test
  void codOrderIsMarkedPaidOnDeliveryUnlessAlreadyCaptured() {
    OrderRepository.Row unpaid = stub("OUT_FOR_DELIVERY", "COD", "NONE", 0);
    service.adminChangeStatus(unpaid.id(), OrderStatus.DELIVERED, null, actor);
    verify(repo).setPaymentStatus(unpaid.id(), "CAPTURED", NOW);

    OrderRepository.Row paid = stub("OUT_FOR_DELIVERY", "COD", "CAPTURED", 0);
    service.adminChangeStatus(paid.id(), OrderStatus.DELIVERED, null, actor);
    verify(repo, never()).setPaymentStatus(eq(paid.id()), anyString(), any());

    UUID onlineId = UUID.randomUUID();
    OrderRepository.Row online = withFulfilment(row(onlineId, "READY_FOR_PICKUP", "ONLINE", "CAPTURED", 0, null), "PICKUP");
    when(repo.find(onlineId)).thenReturn(Optional.of(online));
    service.adminChangeStatus(online.id(), OrderStatus.PICKED_UP, null, actor);
    verify(repo, never()).setPaymentStatus(eq(online.id()), anyString(), any());
  }

  @Test
  void capturedPaymentForAnUnknownOrderIsIgnored() {
    UUID id = UUID.randomUUID();
    when(repo.find(id)).thenReturn(Optional.empty());
    service.on(new PaymentEvents.PaymentCaptured(id, "pay_1"));
    verify(repo, never()).setPaymentStatus(any(), anyString(), any());
    verify(payments, never()).refund(any(), anyString());
  }

  @Test
  void capturedPaymentMovesAPendingOrderToPlaced() {
    OrderRepository.Row r = stub("PAYMENT_PENDING", "ONLINE", "INITIATED", 0);
    service.on(new PaymentEvents.PaymentCaptured(r.id(), "pay_1"));
    verify(repo).updateStatus(r.id(), "PLACED", 2, NOW);
    verify(repo).setPaymentStatus(r.id(), "CAPTURED", NOW);
  }

  @Test
  void paymentAfterExpiryIsRefundedAndRecorded() {
    OrderRepository.Row r = stub("CANCELLED", "ONLINE", "FAILED", 0);
    when(payments.refund(r.id(), "paid after expiry")).thenReturn(true);
    service.on(new PaymentEvents.PaymentCaptured(r.id(), "pay_1"));
    verify(repo).setPaymentStatus(r.id(), "REFUNDED", NOW);
    ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(ev.capture());
    assertThat(ev.getValue()).isInstanceOfSatisfying(OrderChanged.class, c -> assertThat(c.status()).isEqualTo("CANCELLED"));
  }

  @Test
  void paymentAfterExpiryWithNothingToRefundChangesNothing() {
    OrderRepository.Row r = stub("CANCELLED", "ONLINE", "FAILED", 0);
    when(payments.refund(r.id(), "paid after expiry")).thenReturn(false);
    service.on(new PaymentEvents.PaymentCaptured(r.id(), "pay_1"));
    verify(repo, never()).setPaymentStatus(any(), anyString(), any());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test
  void paymentArrivingAfterStaffMovedTheOrderOnIsRecordedWithoutChangingStatus() {
    OrderRepository.Row r = stub("CONFIRMED", "ONLINE", "INITIATED", 0);
    service.on(new PaymentEvents.PaymentCaptured(r.id(), "pay_1"));
    verify(repo).setPaymentStatus(r.id(), "CAPTURED", NOW);
    verify(repo, never()).updateStatus(any(), anyString(), anyLong(), any());
    ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(ev.capture());
    assertThat(ev.getValue()).isInstanceOfSatisfying(OrderChanged.class, c -> {
      assertThat(c.status()).isEqualTo("CONFIRMED");
      assertThat(c.previousStatus()).isEqualTo("CONFIRMED");
      assertThat(c.orderId()).isEqualTo(r.id());
    });
  }

  @Test
  void expiryCancelsEachUnpaidOrderAndKeepsGoingWhenOneFails() {
    OrderRepository.Row bad = stub("PAYMENT_PENDING", "ONLINE", "INITIATED", 0);
    OrderRepository.Row good = stub("PAYMENT_PENDING", "ONLINE", "INITIATED", 0);
    OrderRepository.Row notPending = stub("PLACED", "ONLINE", "CAPTURED", 0);
    when(repo.expiredPending(NOW, 100)).thenReturn(List.of(bad.id(), notPending.id(), good.id()));
    doThrow(new IllegalStateException("db down")).when(repo).updateStatus(eq(bad.id()), anyString(), anyLong(), any());
    service.expireUnpaid();
    verify(repo).updateStatus(good.id(), "CANCELLED", 2, NOW);
    verify(repo, never()).updateStatus(eq(notPending.id()), anyString(), anyLong(), any());
  }

  @Test
  void adminListWithoutSearchTextDelegatesAndMapsCustomersAndMissingCustomers() {
    UUID known = UUID.randomUUID();
    UUID gone = UUID.randomUUID();
    var r1 = new OrderRepository.SummaryRow(UUID.randomUUID(), known, "VSM-1", "PLACED", "DELIVERY", 500, 2, NOW, 450, "C", 10, 1, 5, 40, "COD", "NONE");
    var r2 = new OrderRepository.SummaryRow(UUID.randomUUID(), gone, "VSM-2", "PLACED", "PICKUP", 700, 1, NOW, 700, null, 0, 0, 0, 0, "ONLINE", "CAPTURED");
    when(repo.adminList("PLACED", null, 0, 50)).thenReturn(new Page<>(List.of(r1, r2), 0, 50, 2));
    when(profiles.users(anyList())).thenReturn(Map.of(known, new User(known, "k@x.com", "Kay", "98", "CUSTOMER")));

    Page<OrderViews.AdminSummary> p = service.adminList("PLACED", 0, 500);

    assertThat(p.total()).isEqualTo(2);
    assertThat(p.items()).hasSize(2);
    assertThat(p.items().get(0).customerName()).isEqualTo("Kay");
    assertThat(p.items().get(0).customerEmail()).isEqualTo("k@x.com");
    assertThat(p.items().get(0).customerMobile()).isEqualTo("98");
    assertThat(p.items().get(0).couponCode()).isEqualTo("C");
    assertThat(p.items().get(0).deliveryFeePaise()).isEqualTo(40);
    assertThat(p.items().get(1).customerName()).isNull();
    assertThat(p.items().get(1).customerEmail()).isNull();
    assertThat(p.items().get(1).customerMobile()).isNull();
    assertThat(p.items().get(1).paymentStatus()).isEqualTo("CAPTURED");
  }

  @Test
  void countsAndNumbersAreDelegatedToTheRepository() {
    UUID a = UUID.randomUUID();
    when(repo.openOrderCount(user)).thenReturn(3L);
    when(repo.numbers(List.of(a))).thenReturn(Map.of(a, "VSM-7"));
    assertThat(service.openOrderCount(user)).isEqualTo(3);
    assertThat(service.orderNumbers(List.of(a))).containsEntry(a, "VSM-7");
    assertThat(service.numbers(List.of(a))).containsEntry(a, "VSM-7");
    when(repo.latestCompletedOrderWith(user, a)).thenReturn(Optional.of(a));
    assertThat(service.deliveredOrderWith(user, a)).contains(a);
  }

  @Test
  void anUnreadableAddressSnapshotShowsAsNoAddress() {
    UUID id = UUID.randomUUID();
    when(repo.find(id)).thenReturn(Optional.of(row(id, "PLACED", "COD", "NONE", 0, "{not json")));
    when(wallet.balance(user)).thenReturn(12);
    OrderViews.View v = service.get(user, id);
    assertThat(v.address()).isNull();
    assertThat(v.coinBalance()).isEqualTo(12);
    assertThat(v.paymentSession()).isNull();
  }

  @Test
  void aReadableAddressSnapshotIsReturned() {
    UUID id = UUID.randomUUID();
    String snap = "{\"id\":\"" + UUID.randomUUID() + "\",\"label\":\"Home\",\"line1\":\"12 Main\",\"city\":\"Station\",\"pincode\":\"390001\",\"isDefault\":true}";
    when(repo.find(id)).thenReturn(Optional.of(row(id, "PLACED", "COD", "NONE", 0, snap)));
    assertThat(service.get(user, id).address()).isNotNull();
    assertThat(service.adminGet(id).customerEmail()).isEqualTo("a@b.com");
  }

  @Test
  void anotherCustomersOrderIsNotFound() {
    OrderRepository.Row r = stub("PLACED", "COD", "NONE", 0);
    assertThatThrownBy(() -> service.get(UUID.randomUUID(), r.id())).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.customerCancel(UUID.randomUUID(), r.id())).isInstanceOf(ApiException.class);
  }

  @Test
  void customerCancelKeepsTheTrimmedReasonAndCreditsNothing() {
    OrderRepository.Row r = stub("PLACED", "COD", "NONE", 5);
    service.customerCancel(user, r.id(), "  changed my mind ");
    verify(repo).setCancelInfo(r.id(), "CUSTOMER", "CUSTOMER_CANCELLED", "changed my mind");
    verify(wallet, never()).credit(any(), anyInt(), any());
    verify(inventory, never()).release(anyList(), any());
    verify(repo, never()).markCoinsCredited(any());
    verify(repo, never()).setStockReserved(any(), anyBoolean());
  }

  private static int anyInt() { return ArgumentMatchers.anyInt(); }
}
