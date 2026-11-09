package com.vijay.mall.order.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.domain.PaymentEvents;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A wrong-amount or duplicate payment leaves the order alone and only produces a notification event. */
class OrderPaymentAnomalyTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private OrderRepository repo;
  private OutboxService outbox;
  private OrderService service;
  private final UUID user = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(OrderRepository.class);
    outbox = mock(OutboxService.class);
    AppProps props = new AppProps(null, null, null, null,
        new AppProps.Payment("fake", 15, null, null, null, null), new AppProps.Delivery(4000, 49900));
    service = new OrderService(repo, mock(QuoteService.class), mock(InventoryService.class), mock(WalletService.class),
        mock(PromotionService.class), mock(PaymentService.class), mock(ProfileService.class), outbox, mock(AuditService.class),
        JsonMapper.builder().build(), new TransactionTemplate(mock(PlatformTransactionManager.class)), props,
        Clock.fixed(NOW, ZoneOffset.UTC), mock(ApplicationEventPublisher.class));
  }

  private UUID stub(String status) {
    UUID id = UUID.randomUUID();
    when(repo.find(id)).thenReturn(Optional.of(new OrderRepository.Row(id, "VSM-9", user, "DELIVERY", status, 10000, null, 0, 0, 0, 0,
        10000, 0, false, "ONLINE", "INITIATED", null, null, NOW, 2, null, null, false)));
    return id;
  }

  @Test
  void anOrderStillWaitingForPaymentIsReportedAsPayable() {
    UUID id = stub("PAYMENT_PENDING");
    service.on(new PaymentEvents.PaymentAnomaly(id, "MISMATCH", 10000, 9000));
    verify(outbox).add("PAYMENT_ANOMALY", Map.of("orderId", id, "orderNo", "VSM-9", "userId", user, "kind", "MISMATCH",
        "expectedPaise", 10000L, "receivedPaise", 9000L, "payable", true));
    verify(repo, never()).updateStatus(any(), anyString(), anyLong(), any());
  }

  @Test
  void anOrderThatIsAlreadyPaidIsNotPayable() {
    UUID id = stub("PLACED");
    service.on(new PaymentEvents.PaymentAnomaly(id, "DUPLICATE", 10000, 10000));
    verify(outbox).add("PAYMENT_ANOMALY", Map.of("orderId", id, "orderNo", "VSM-9", "userId", user, "kind", "DUPLICATE",
        "expectedPaise", 10000L, "receivedPaise", 10000L, "payable", false));
  }

  @Test
  void anUnknownOrderProducesNothing() {
    UUID id = UUID.randomUUID();
    when(repo.find(id)).thenReturn(Optional.empty());
    service.on(new PaymentEvents.PaymentAnomaly(id, "MISMATCH", 1, 2));
    verify(outbox, never()).add(anyString(), any());
  }
}
