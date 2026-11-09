package com.vijay.mall.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** A flash sale can split one product into two order lines (sale price, normal price); returns must still work. */
class OrderReturnSplitLinesTest {
  private final UUID actor = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final UUID saleLine = UUID.randomUUID();
  private final UUID normalLine = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
  private OrderReturnRepository returns;
  private InventoryService inventory;
  private OrderReturnService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    OrderRepository orders = mock(OrderRepository.class);
    returns = mock(OrderReturnRepository.class);
    inventory = mock(InventoryService.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    service = new OrderReturnService(orders, returns, inventory, mock(WalletService.class), mock(PaymentService.class), mock(OutboxService.class),
        mock(AuditService.class), tx, Clock.fixed(now, ZoneOffset.UTC), mock(ApplicationEventPublisher.class));
    when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
    when(returns.findByIdem(anyString())).thenReturn(Optional.empty());
    when(returns.totals(orderId)).thenReturn(new OrderReturnRepository.Totals(0, 0, 0));
    when(returns.addReturned(any(), anyInt())).thenReturn(true);
    // 3 units at the flash price of Rs 40 and 2 at the normal Rs 50: same product, two rows
    when(orders.find(orderId)).thenReturn(Optional.of(new OrderRepository.Row(orderId, "VSM-9", UUID.randomUUID(), "DELIVERY", "DELIVERED", 22000,
        null, 0, 0, 0, 0, 22000, 0, false, "COD", "NONE", null, null, now, 0)));
    when(orders.itemRows(orderId)).thenReturn(List.of(
        new OrderRepository.ItemRow(saleLine, product, "Sugar", 3, 0, 4000, 12000, 0),
        new OrderRepository.ItemRow(normalLine, product, "Sugar", 2, 0, 5000, 10000, 0)));
    when(returns.find(any())).thenAnswer(i -> Optional.of(new OrderReturnRepository.Return(i.getArgument(0), orderId, "r", 0, "NONE", 0, 0, actor, now, List.of())));
  }

  private void giveBack(int qty) {
    service.create(actor, orderId, "k" + qty, new OrderReturnService.Input("damaged", List.of(new OrderReturnService.ItemInput(product, qty, true))));
  }

  @Test
  void unitsComeBackFromTheSaleLineFirstAndSpillOverToTheNormalLine() {
    giveBack(4); // 3 from the sale line, 1 from the normal line
    ArgumentCaptor<List<OrderReturnRepository.Line>> stored = ArgumentCaptor.forClass(List.class);
    verify(returns).insert(any(), eq(orderId), anyString(), anyString(), ArgumentMatchers.anyLong(), anyString(), anyInt(), anyInt(), eq(actor),
        stored.capture(), any(), ArgumentMatchers.anyLong());
    assertThat(stored.getValue()).extracting(OrderReturnRepository.Line::orderItemId).containsExactly(saleLine, normalLine);
    assertThat(stored.getValue()).extracting(OrderReturnRepository.Line::qty).containsExactly(3, 1);
    assertThat(stored.getValue().stream().mapToLong(OrderReturnRepository.Line::refundPaise).sum()).isEqualTo(12000 + 5000);
    verify(returns).addReturned(saleLine, 3);
    verify(returns).addReturned(normalLine, 1);
    ArgumentCaptor<List<InventoryService.Item>> shelf = ArgumentCaptor.forClass(List.class);
    verify(inventory).release(shelf.capture(), any(), eq("ORDER_RETURN"));
    assertThat(shelf.getValue().stream().mapToInt(InventoryService.Item::qty).sum()).isEqualTo(4);
  }

  @Test
  void aSmallReturnTouchesOnlyTheFirstLine() {
    giveBack(2);
    verify(returns).addReturned(saleLine, 2);
    verify(returns, Mockito.never()).addReturned(eq(normalLine), anyInt());
  }

  @Test
  void youCannotReturnMoreThanBothLinesTogether() {
    assertThatThrownBy(() -> giveBack(6)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo("RETURN_EXCEEDS_SOLD");
      assertThat(e.details()).containsEntry("returnable", 5);
    });
    giveBack(5); // exactly everything is fine
  }
}
