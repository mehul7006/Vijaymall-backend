package com.vijay.mall.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.order.infra.OrderReturnRepository;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.wallet.app.WalletService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Every validation, money and mode branch of order returns, with the collaborators mocked. */
class OrderReturnServiceTest {
  private OrderRepository orders;
  private OrderReturnRepository returns;
  private InventoryService inventory;
  private WalletService wallet;
  private PaymentService payments;
  private TransactionTemplate tx;
  private OrderReturnService service;
  private final UUID actor = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();
  private final UUID lineA = UUID.randomUUID();
  private final UUID lineB = UUID.randomUUID();
  private final UUID prodA = UUID.randomUUID();
  private final UUID prodB = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    orders = mock(OrderRepository.class);
    returns = mock(OrderReturnRepository.class);
    inventory = mock(InventoryService.class);
    wallet = mock(WalletService.class);
    payments = mock(PaymentService.class);
    tx = mock(TransactionTemplate.class);
    service = new OrderReturnService(orders, returns, inventory, wallet, payments, mock(OutboxService.class), mock(AuditService.class),
        tx, Clock.fixed(now, ZoneOffset.UTC), mock(ApplicationEventPublisher.class));
    when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
    when(returns.findByIdem(anyString())).thenReturn(Optional.empty());
    when(returns.totals(orderId)).thenReturn(new OrderReturnRepository.Totals(0, 0, 0));
    when(returns.addReturned(any(), anyInt())).thenReturn(true);
    when(wallet.clawback(any(), anyInt(), any())).thenAnswer(i -> i.getArgument(1));
    order("DELIVERED", "COD", false, 0, 0);
    items(2);
  }

  private void order(String status, String payMethod, boolean credited, int coinsUsed, long coinDiscount) {
    when(orders.find(orderId)).thenReturn(Optional.of(new OrderRepository.Row(orderId, "VSM-9", user, "DELIVERY", status, 40000,
        null, 0, coinsUsed, coinDiscount, 0, 40000, 40, credited, payMethod, "CAPTURED", null, null, now, 0)));
  }

  private void items(int lines) {
    var a = new OrderRepository.ItemRow(lineA, prodA, "Apple", 4, 0, 5000, 20000, 0);
    var b = new OrderRepository.ItemRow(lineB, prodB, "Banana", 4, 0, 5000, 20000, 0);
    when(orders.itemRows(orderId)).thenReturn(lines == 2 ? List.of(a, b) : List.of(a));
  }

  private void stubFind() {
    when(returns.find(any())).thenAnswer(i -> Optional.of(new OrderReturnRepository.Return(i.getArgument(0), orderId, "r", 0, "NONE",
        0, 0, actor, now, List.of(new OrderReturnRepository.Line(lineA, 1, 0, true), new OrderReturnRepository.Line(UUID.randomUUID(), 1, 0, true)))));
  }

  private OrderReturnService.ReturnView create(OrderReturnService.Input in) {
    return service.create(actor, orderId, "k1", in);
  }

  private static OrderReturnService.Input in(String reason, OrderReturnService.ItemInput... items) {
    return new OrderReturnService.Input(reason, List.of(items));
  }

  private OrderReturnService.ItemInput item(UUID product, int qty, Boolean restock) {
    return new OrderReturnService.ItemInput(product, qty, restock);
  }

  private void rejects(OrderReturnService.Input in, String code) {
    assertThatThrownBy(() -> create(in)).isInstanceOf(ApiException.class);
    try { create(in); } catch (ApiException e) { assertThat(e.code()).isEqualTo(code); }
  }

  @Test
  void idempotencyKeyIsRequired() {
    assertThatThrownBy(() -> service.create(actor, orderId, null, in("r"))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.create(actor, orderId, "  ", in("r"))).isInstanceOf(ApiException.class);
  }

  @Test
  void replayReturnsTheStoredReturn() {
    stubFind();
    var stored = returns.find(UUID.randomUUID()).orElseThrow();
    when(returns.findByIdem("k1")).thenReturn(Optional.of(stored));
    assertThat(create(in("r")).items()).hasSize(2);
    verify(tx, never()).execute(any());
  }

  @Test
  void raceOnTheKeyReturnsTheWinner() {
    stubFind();
    var stored = returns.find(UUID.randomUUID()).orElseThrow();
    when(returns.findByIdem("k1")).thenReturn(Optional.empty(), Optional.of(stored));
    org.mockito.Mockito.doThrow(new DataIntegrityViolationException("dup")).when(tx).execute(any());
    assertThat(create(in("r")).orderNo()).isEqualTo("VSM-9");
  }

  @Test
  void raceWithoutAWinnerRethrows() {
    var boom = new DataIntegrityViolationException("dup");
    org.mockito.Mockito.doThrow(boom).when(tx).execute(any());
    assertThatThrownBy(() -> create(in("r"))).isSameAs(boom);
  }

  @Test
  void unknownOrderIsNotFound() {
    when(orders.find(orderId)).thenReturn(Optional.empty());
    rejects(in("r", item(prodA, 1, null)), "NOT_FOUND");
  }

  @Test
  void onlyDeliveredOrPickedUpOrdersQualify() {
    order("PLACED", "COD", false, 0, 0);
    rejects(in("r", item(prodA, 1, null)), "ORDER_NOT_DELIVERED");
    order("PICKED_UP", "COD", false, 0, 0);
    stubFind();
    assertThat(create(in("r", item(prodA, 1, null)))).isNotNull();
  }

  @Test
  void reasonAndItemsAreValidated() {
    rejects(new OrderReturnService.Input(null, List.of(item(prodA, 1, null))), "VALIDATION_FAILED");
    rejects(in("   ", item(prodA, 1, null)), "VALIDATION_FAILED");
    rejects(in("x".repeat(201), item(prodA, 1, null)), "VALIDATION_FAILED");
    rejects(new OrderReturnService.Input("r", null), "VALIDATION_FAILED");
    rejects(in("r"), "VALIDATION_FAILED");
    var many = java.util.stream.IntStream.range(0, 101).mapToObj(k -> item(prodA, 1, null)).toList();
    rejects(new OrderReturnService.Input("r", many), "VALIDATION_FAILED");
  }

  @Test
  void itemsAreValidated() {
    rejects(in("r", item(null, 1, null)), "VALIDATION_FAILED");
    rejects(in("r", item(UUID.randomUUID(), 1, null)), "VALIDATION_FAILED");
    rejects(in("r", item(prodA, 1, null), item(prodA, 1, null)), "VALIDATION_FAILED");
    rejects(in("r", item(prodA, 0, null)), "VALIDATION_FAILED");
    rejects(in("r", item(prodA, 5, null)), "RETURN_EXCEEDS_SOLD");
  }

  @Test
  void lostRaceOnTheCounterIsAConflict() {
    when(returns.addReturned(lineA, 1)).thenReturn(false);
    rejects(in("r", item(prodA, 1, null)), "RETURN_EXCEEDS_SOLD");
  }

  @Test
  void refundCannotExceedTheOrder() {
    when(returns.totals(orderId)).thenReturn(new OrderReturnRepository.Totals(39000, 0, 0));
    rejects(in("r", item(prodA, 1, null)), "REFUND_EXCEEDS_ORDER");
  }

  @Test
  void codReturnIsCashAndSkipsCoinClawbackWhenNotCredited() {
    stubFind();
    var v = create(in("r", item(prodA, 2, true), item(prodB, 1, false)));
    assertThat(v.items()).extracting(OrderReturnService.ItemView::name).containsExactly("Apple", "?");
    assertThat(v.items().get(1).productId()).isNull();
    verify(wallet, never()).clawback(any(), anyInt(), any());
    verify(wallet).restore(eq(user), anyInt(), any());
    // no defective ledger wired here, so the item returned with restock=false is put back too (the units are never lost)
    verify(inventory, org.mockito.Mockito.times(2)).release(anyList(), any(), eq("ORDER_RETURN"));
    verify(returns).insert(any(), eq(orderId), eq("k1"), eq("r"), eq(15000L), eq("CASH"), eq(0), anyInt(), eq(actor), anyList(), eq(now));
    verify(payments, never()).refundPartial(any(), anyLong(), anyString());
  }

  @Test
  void goodsReturnedAsDefectiveGoToTheReviewQueueNotTheShelf() {
    var defective = mock(com.vijay.mall.inventory.app.DefectiveService.class);
    service.setDefective(defective);
    stubFind();
    create(in("leaking", item(prodA, 2, true), item(prodB, 1, false)));
    verify(inventory, org.mockito.Mockito.times(1)).release(anyList(), any(), eq("ORDER_RETURN")); // only the resellable item
    verify(defective).record(anyList(), eq("ORDER_RETURN"), any(), eq(actor), eq("leaking"));
  }

  @Test
  void onlineReturnIsRefundedAndCreditedCoinsClawedBack() {
    order("DELIVERED", "ONLINE", true, 100, 1000);
    stubFind();
    when(payments.refundPartial(eq(orderId), anyLong(), anyString())).thenReturn(true);
    create(in("r", item(prodA, 1, null)));
    verify(wallet).clawback(eq(user), anyInt(), any());
    verify(returns).insert(any(), eq(orderId), eq("k1"), eq("r"), anyLong(), eq("ONLINE"), anyInt(), anyInt(), eq(actor), anyList(), eq(now));
  }

  @Test
  void onlineRefundThatTheGatewayCannotDoFallsToCash() {
    order("DELIVERED", "ONLINE", false, 0, 0);
    stubFind();
    when(payments.refundPartial(eq(orderId), anyLong(), anyString())).thenReturn(false);
    create(in("r", item(prodA, 1, null)));
    verify(returns).insert(any(), eq(orderId), eq("k1"), eq("r"), anyLong(), eq("CASH"), anyInt(), anyInt(), eq(actor), anyList(), eq(now));
  }

  @Test
  void zeroValueReturnHasNoRefundAndNoRestockWhenNotWanted() {
    items(1);
    when(orders.itemRows(orderId)).thenReturn(List.of(new OrderRepository.ItemRow(lineA, prodA, "Free", 2, 0, 0, 0, 0)));
    stubFind();
    var defective = mock(com.vijay.mall.inventory.app.DefectiveService.class);
    service.setDefective(defective);
    create(in("r", item(prodA, 1, false)));
    verify(inventory, never()).release(anyList(), any(), anyString());
    verify(defective).record(anyList(), eq("ORDER_RETURN"), any(), eq(actor), eq("r"));
    verify(returns).insert(any(), eq(orderId), eq("k1"), eq("r"), eq(0L), eq("NONE"), anyInt(), anyInt(), eq(actor), anyList(), eq(now));
  }

  @Test
  void forOrderRespectsOwnership() {
    stubFind();
    var stored = returns.find(UUID.randomUUID()).orElseThrow();
    when(returns.forOrder(orderId)).thenReturn(List.of(stored));
    assertThat(service.forOrder(orderId, null)).hasSize(1);
    assertThat(service.forOrder(orderId, user)).hasSize(1);
    assertThatThrownBy(() -> service.forOrder(orderId, UUID.randomUUID())).isInstanceOf(ApiException.class);
  }

  @Test
  void viewShowsAQuestionMarkWhenTheOrderIsGone() {
    stubFind();
    var stored = returns.find(UUID.randomUUID()).orElseThrow();
    when(returns.findByIdem("k1")).thenReturn(Optional.of(stored));
    when(orders.find(orderId)).thenReturn(Optional.empty());
    assertThat(create(in("r")).orderNo()).isEqualTo("?");
  }
}
