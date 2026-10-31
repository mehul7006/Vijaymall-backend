package com.vijay.mall.order.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.json.JsonMapper;
import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.identity.domain.Address;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.OrderStatus;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.domain.PaymentEvents;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinRedemption;
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

/** Order use cases with every collaborator replaced by a mock. */
class OrderServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private OrderRepository repo;
  private QuoteService quotes;
  private InventoryService inventory;
  private WalletService wallet;
  private PromotionService promotions;
  private PaymentService payments;
  private ProfileService profiles;
  private OutboxService outbox;
  private OrderService service;

  private final UUID user = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();
  private final UUID addressId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(OrderRepository.class);
    quotes = mock(QuoteService.class);
    inventory = mock(InventoryService.class);
    wallet = mock(WalletService.class);
    promotions = mock(PromotionService.class);
    payments = mock(PaymentService.class);
    profiles = mock(ProfileService.class);
    outbox = mock(OutboxService.class);
    AppProps props = new AppProps(null, null, null, null,
        new AppProps.Payment("fake", 15, null, null, null, null), new AppProps.Delivery(4000, 49900));
    service = new OrderService(repo, quotes, inventory, wallet, promotions, payments, profiles, outbox,
        mock(AuditService.class), JsonMapper.builder().build(), new TransactionTemplate(mock(PlatformTransactionManager.class)),
        props, Clock.fixed(NOW, ZoneOffset.UTC));
    when(repo.nextOrderNo()).thenReturn("VSM-1001");
    when(repo.findByIdem(any(), anyString())).thenReturn(Optional.empty());
    when(repo.items(any())).thenReturn(List.of());
    when(repo.timeline(any())).thenReturn(List.of());
    when(payments.sessionFor(any())).thenReturn(Optional.empty());
    when(profiles.address(user, addressId)).thenReturn(new Address(addressId, "Home", "12 Main", "Station", "390001", true));
    when(profiles.get(any())).thenReturn(new User(user, "a@b.com", "Asha", "9999999999", "CUSTOMER"));
  }

  private Quote quote(long total, int coinsUsed, String coupon) {
    return new Quote(List.of(new Quote.Line(product, "Rice", 2, 5000, 10000, true, true)), 10000, coupon, coupon == null ? 0 : 500,
        coinsUsed, coinsUsed * 100L, 0, total, 7, List.of());
  }

  private OrderService.PlaceCommand cmd(String method, long expected, UUID address, Fulfilment f) {
    QuoteRequest q = new QuoteRequest(List.of(new CatalogService.ItemRequest(product, 2)), null, CoinRedemption.Mode.NONE, null, f);
    return new OrderService.PlaceCommand(q, address, method, expected);
  }

  private OrderRepository.Row row(String status, String method, String payStatus, int coinsUsed, int coinsEarned,
                                  boolean credited, UUID owner) {
    return new OrderRepository.Row(UUID.randomUUID(), "VSM-1001", owner, "DELIVERY", status, 10000, null, 0, coinsUsed,
        coinsUsed * 100L, 0, 10000, coinsEarned, credited, method, payStatus, null, null, NOW, 3);
  }

  private void stubPlaced(String status) {
    when(repo.find(any())).thenAnswer(i -> Optional.of(new OrderRepository.Row((UUID) i.getArgument(0), "VSM-1001", user,
        "DELIVERY", status, 10000, null, 0, 0, 0, 0, 10000, 7, false, "COD", "NONE", null, null, NOW, 0)));
  }

  // ---------- place ----------
  @Test
  void codOrderIsPlacedAndEverythingIsReservedAndRecorded() {
    when(quotes.quote(eq(user), any())).thenReturn(quote(10000, 20, "FESTIVE50"));
    stubPlaced("PLACED");
    service.place(user, "key-1", cmd("COD", 10000, addressId, Fulfilment.DELIVERY));

    ArgumentCaptor<OrderRepository.NewOrder> o = ArgumentCaptor.forClass(OrderRepository.NewOrder.class);
    verify(repo).insert(o.capture());
    assertEquals("PLACED", o.getValue().status());
    assertEquals("NONE", o.getValue().paymentStatus());
    assertNull(o.getValue().expiresAt());
    assertEquals("VSM-1001", o.getValue().orderNo());
    assertEquals(7, o.getValue().coinsEarned());
    verify(repo).insertItem(eq(o.getValue().id()), any());
    verify(repo).insertHistory(eq(o.getValue().id()), eq(null), eq("PLACED"), eq(user), eq(null), any());
    verify(inventory).reserve(eq(List.of(new InventoryService.Item(product, 2))), eq(o.getValue().id()));
    verify(wallet).redeem(user, 20, o.getValue().id());
    verify(promotions).consume("FESTIVE50", user, o.getValue().id(), 500);
    verify(payments).start(o.getValue().id(), "VSM-1001", 10000, "COD");
    verify(outbox).add(eq("ORDER_PLACED"), any());
  }

  @Test
  void onlineOrderWaitsForPaymentAndExpires() {
    when(quotes.quote(eq(user), any())).thenReturn(quote(10000, 0, null));
    stubPlaced("PAYMENT_PENDING");
    service.place(user, "key-2", cmd("ONLINE", 10000, addressId, Fulfilment.DELIVERY));

    ArgumentCaptor<OrderRepository.NewOrder> o = ArgumentCaptor.forClass(OrderRepository.NewOrder.class);
    verify(repo).insert(o.capture());
    assertEquals("PAYMENT_PENDING", o.getValue().status());
    assertEquals("INITIATED", o.getValue().paymentStatus());
    assertEquals(NOW.plusSeconds(15 * 60), o.getValue().expiresAt());
    verify(payments).requireEnabled("ONLINE");
  }

  @Test
  void zeroTotalOnlineOrderSkipsPayment() {
    when(quotes.quote(eq(user), any())).thenReturn(quote(0, 0, null));
    stubPlaced("PLACED");
    service.place(user, "key-3", cmd("ONLINE", 0, addressId, Fulfilment.DELIVERY));
    ArgumentCaptor<OrderRepository.NewOrder> o = ArgumentCaptor.forClass(OrderRepository.NewOrder.class);
    verify(repo).insert(o.capture());
    assertEquals("PLACED", o.getValue().status());
    assertNull(o.getValue().expiresAt());
  }

  @Test
  void changedPriceIsRejectedBeforeAnythingIsWritten() {
    Quote q = quote(10000, 0, null);
    when(quotes.quote(eq(user), any())).thenReturn(q);
    ApiException e = assertThrows(ApiException.class, () -> service.place(user, "key-4", cmd("COD", 9999, addressId, Fulfilment.DELIVERY)));
    assertEquals("PRICE_CHANGED", e.code());
    assertEquals(409, e.status().value());
    assertEquals(q, e.details().get("quote"));
    verify(repo, never()).insert(any());
    verify(inventory, never()).reserve(any(), any());
  }

  @Test
  void sameIdempotencyKeyReturnsTheFirstOrderWithoutPlacingAgain() {
    OrderRepository.Row first = row("PLACED", "COD", "NONE", 0, 7, false, user);
    when(repo.findByIdem(user, "key-5")).thenReturn(Optional.of(first));
    var view = service.place(user, "key-5", cmd("COD", 10000, addressId, Fulfilment.DELIVERY));
    assertEquals(first.id(), view.id());
    verify(quotes, never()).quote(any(), any());
    verify(repo, never()).insert(any());
  }

  @Test
  void missingKeyDeliveryAddressAndBadMethodAreValidationErrors() {
    assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class,
        () -> service.place(user, " ", cmd("COD", 1, addressId, Fulfilment.DELIVERY))).code());
    when(quotes.quote(eq(user), any())).thenReturn(quote(10000, 0, null));
    assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class,
        () -> service.place(user, "k6", cmd("COD", 10000, null, Fulfilment.DELIVERY))).code());
    assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class,
        () -> service.place(user, "k7", cmd("BARTER", 10000, addressId, Fulfilment.DELIVERY))).code());
  }

  @Test
  void pickupNeedsNoAddress() {
    when(quotes.quote(eq(user), any())).thenReturn(quote(10000, 0, null));
    stubPlaced("PLACED");
    service.place(user, "k8", cmd("COD", 10000, null, Fulfilment.PICKUP));
    verify(profiles, never()).address(any(), any());
    verify(repo).insert(any());
  }

  @Test
  void outOfStockStopsTheOrderBeforeCoinsAndCouponAreTouched() {
    when(quotes.quote(eq(user), any())).thenReturn(quote(10000, 20, "FESTIVE50"));
    org.mockito.Mockito.doThrow(ApiException.conflict("OUT_OF_STOCK", "x", java.util.Map.of())).when(inventory).reserve(anyList(), any());
    assertEquals("OUT_OF_STOCK", assertThrows(ApiException.class,
        () -> service.place(user, "k9", cmd("COD", 10000, addressId, Fulfilment.DELIVERY))).code());
    verify(wallet, never()).redeem(any(), anyInt(), any());
    verify(promotions, never()).consume(any(), any(), any(), anyLong());
    verify(payments, never()).start(any(), any(), anyLong(), any());
  }

  // ---------- status changes ----------
  @Test
  void adminCanMoveAnOrderForwardOneLegalStep() {
    OrderRepository.Row r = row("PLACED", "COD", "NONE", 0, 7, false, user);
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    when(repo.updateStatus(eq(r.id()), eq("CONFIRMED"), eq(3L), any())).thenReturn(true);
    service.adminChangeStatus(r.id(), OrderStatus.CONFIRMED, "ok", UUID.randomUUID());
    verify(repo).insertHistory(eq(r.id()), eq("PLACED"), eq("CONFIRMED"), any(), eq("ok"), any());
    verify(outbox).add(eq("ORDER_STATUS"), any());
    verify(wallet, never()).credit(any(), anyInt(), any());
  }

  @Test
  void skippingStepsIsIllegal() {
    OrderRepository.Row r = row("PLACED", "COD", "NONE", 0, 7, false, user);
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    ApiException e = assertThrows(ApiException.class, () -> service.adminChangeStatus(r.id(), OrderStatus.DELIVERED, null, user));
    assertEquals("ILLEGAL_TRANSITION", e.code());
    verify(repo, never()).updateStatus(any(), any(), anyLong(), any());
  }

  @Test
  void concurrentChangeIsReportedAsVersionConflict() {
    OrderRepository.Row r = row("PLACED", "COD", "NONE", 0, 7, false, user);
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    when(repo.updateStatus(any(), any(), anyLong(), any())).thenReturn(false);
    assertEquals("VERSION_CONFLICT", assertThrows(ApiException.class,
        () -> service.adminChangeStatus(r.id(), OrderStatus.CONFIRMED, null, user)).code());
    verify(repo, never()).insertHistory(any(), any(), any(), any(), any(), any());
  }

  @Test
  void deliveringACodOrderCreditsCoinsOnceAndMarksCashPaid() {
    OrderRepository.Row r = row("OUT_FOR_DELIVERY", "COD", "NONE", 0, 12, false, user);
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    when(repo.updateStatus(any(), eq("DELIVERED"), anyLong(), any())).thenReturn(true);
    service.adminChangeStatus(r.id(), OrderStatus.DELIVERED, null, user);
    verify(wallet).credit(user, 12, r.id());
    verify(repo).markCoinsCredited(r.id());
    verify(repo).setPaymentStatus(eq(r.id()), eq("CAPTURED"), any());
  }

  @Test
  void coinsAreNeverCreditedTwice() {
    OrderRepository.Row r = row("OUT_FOR_DELIVERY", "COD", "NONE", 0, 12, true, user);
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    when(repo.updateStatus(any(), eq("DELIVERED"), anyLong(), any())).thenReturn(true);
    service.adminChangeStatus(r.id(), OrderStatus.DELIVERED, null, user);
    verify(wallet, never()).credit(any(), anyInt(), any());
  }

  // ---------- cancel ----------
  @Test
  void customerCancelGivesBackStockCouponCoinsAndMoney() {
    OrderRepository.Row r = row("CONFIRMED", "ONLINE", "CAPTURED", 20, 7, false, user);
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    when(repo.updateStatus(any(), eq("CANCELLED"), anyLong(), any())).thenReturn(true);
    when(repo.items(r.id())).thenReturn(List.of(new com.vijay.mall.order.domain.OrderViews.Item(product, "Rice", 2, 5000, 10000)));
    when(payments.refund(eq(r.id()), anyString())).thenReturn(true);

    service.customerCancel(user, r.id());

    verify(inventory).release(List.of(new InventoryService.Item(product, 2)), r.id());
    verify(promotions).release(r.id());
    verify(wallet).reverse(user, r.id());
    verify(payments).refund(eq(r.id()), anyString());
    verify(repo).setPaymentStatus(eq(r.id()), eq("REFUNDED"), any());
  }

  @Test
  void cancelDoesNotTouchCoinsWhenNoneWereUsed() {
    OrderRepository.Row r = row("PLACED", "COD", "NONE", 0, 7, false, user);
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    when(repo.updateStatus(any(), eq("CANCELLED"), anyLong(), any())).thenReturn(true);
    service.customerCancel(user, r.id());
    verify(wallet, never()).reverse(any(), any());
  }

  @Test
  void customerCannotCancelAfterPackingOrSomeoneElsesOrder() {
    OrderRepository.Row packed = row("PACKED", "COD", "NONE", 0, 7, false, user);
    when(repo.find(packed.id())).thenReturn(Optional.of(packed));
    assertEquals("ILLEGAL_TRANSITION", assertThrows(ApiException.class, () -> service.customerCancel(user, packed.id())).code());

    OrderRepository.Row other = row("PLACED", "COD", "NONE", 0, 7, false, UUID.randomUUID());
    when(repo.find(other.id())).thenReturn(Optional.of(other));
    assertEquals("NOT_FOUND", assertThrows(ApiException.class, () -> service.customerCancel(user, other.id())).code());
  }

  // ---------- payment results ----------
  @Test
  void paymentCapturedMovesPendingOrderToPlaced() {
    OrderRepository.Row r = row("PAYMENT_PENDING", "ONLINE", "INITIATED", 0, 7, false, user);
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    when(repo.updateStatus(any(), eq("PLACED"), anyLong(), any())).thenReturn(true);
    service.on(new PaymentEvents.PaymentCaptured(r.id(), "pay_1"));
    verify(repo).setPaymentStatus(eq(r.id()), eq("CAPTURED"), any());
  }

  @Test
  void paymentAfterExpiryIsRefunded() {
    OrderRepository.Row r = row("CANCELLED", "ONLINE", "FAILED", 0, 7, false, user);
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    when(payments.refund(eq(r.id()), anyString())).thenReturn(true);
    service.on(new PaymentEvents.PaymentCaptured(r.id(), "pay_1"));
    verify(payments).refund(eq(r.id()), anyString());
    verify(repo).setPaymentStatus(eq(r.id()), eq("REFUNDED"), any());
    verify(repo, never()).updateStatus(any(), any(), anyLong(), any());
  }

  @Test
  void paymentFailureCancelsAPendingOrder() {
    OrderRepository.Row r = row("PAYMENT_PENDING", "ONLINE", "INITIATED", 0, 7, false, user);
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    when(repo.updateStatus(any(), eq("CANCELLED"), anyLong(), any())).thenReturn(true);
    service.on(new PaymentEvents.PaymentFailed(r.id(), "declined"));
    verify(inventory).release(anyList(), eq(r.id()));
  }

  @Test
  void unpaidOnlineOrdersExpire() {
    OrderRepository.Row r = row("PAYMENT_PENDING", "ONLINE", "INITIATED", 0, 7, false, user);
    when(repo.expiredPending(any(), anyInt())).thenReturn(List.of(r.id()));
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    when(repo.updateStatus(any(), eq("CANCELLED"), anyLong(), any())).thenReturn(true);
    service.expireUnpaid();
    verify(repo, times(1)).insertHistory(eq(r.id()), eq("PAYMENT_PENDING"), eq("CANCELLED"), eq(null), eq("payment timeout"), any());
  }

  @Test
  void ordersOfOtherUsersAreNotVisible() {
    OrderRepository.Row r = row("PLACED", "COD", "NONE", 0, 7, false, UUID.randomUUID());
    when(repo.find(r.id())).thenReturn(Optional.of(r));
    assertEquals("NOT_FOUND", assertThrows(ApiException.class, () -> service.get(user, r.id())).code());
  }
}
