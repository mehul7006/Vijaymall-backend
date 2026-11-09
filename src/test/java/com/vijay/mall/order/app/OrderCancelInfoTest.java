package com.vijay.mall.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.inventory.app.InventoryService;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Who cancelled an order and why is stored at every place an order becomes CANCELLED, and shown without old prefixes. */
class OrderCancelInfoTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private OrderRepository repo;
  private OutboxService outbox;
  private OrderService service;
  private final UUID user = UUID.randomUUID();
  private final UUID staff = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(OrderRepository.class);
    PaymentService payments = mock(PaymentService.class);
    ProfileService profiles = mock(ProfileService.class);
    outbox = mock(OutboxService.class);
    AppProps props = new AppProps(null, null, null, null,
        new AppProps.Payment("fake", 15, null, null, null, null), new AppProps.Delivery(4000, 49900));
    service = new OrderService(repo, mock(QuoteService.class), mock(InventoryService.class), mock(WalletService.class),
        mock(PromotionService.class), payments, profiles, outbox, mock(AuditService.class), JsonMapper.builder().build(),
        new TransactionTemplate(mock(PlatformTransactionManager.class)), props, Clock.fixed(NOW, ZoneOffset.UTC),
        mock(ApplicationEventPublisher.class));
    when(repo.items(any())).thenReturn(List.of());
    when(repo.timeline(any())).thenReturn(List.of());
    when(payments.sessionFor(any())).thenReturn(Optional.empty());
    when(profiles.get(any())).thenReturn(new User(user, "a@b.com", "Asha", "9999999999", "CUSTOMER"));
    when(repo.updateStatus(any(), anyString(), anyLong(), any())).thenReturn(true);
  }

  private OrderRepository.Row stub(String status, String cancelReason) {
    UUID id = UUID.randomUUID();
    var r = new OrderRepository.Row(id, "VSM-9", user, "DELIVERY", status, 10000, null, 0, 0, 0, 0, 10000, 0, false, "ONLINE",
        "NONE", null, null, NOW, 2, null, cancelReason, false, "SHOP", "REJECTED_BY_SHOP");
    when(repo.find(id)).thenReturn(Optional.of(r));
    return r;
  }

  @Test
  void staffCancelThroughTheStatusEndpointKeepsTheTrimmedNoteAsTheReason() {
    var r = stub("PLACED", null);
    service.adminChangeStatus(r.id(), OrderStatus.CANCELLED, "  out of stock ", staff);
    verify(repo).setCancelInfo(r.id(), "SHOP", "CANCELLED_BY_SHOP", "out of stock");
  }

  @Test
  void staffCancelWithABlankOrMissingNoteStillSaysWhoDidIt() {
    var a = stub("PLACED", null);
    service.adminChangeStatus(a.id(), OrderStatus.CANCELLED, "   ", staff);
    verify(repo).setCancelInfo(a.id(), "SHOP", "CANCELLED_BY_SHOP", null);
    var b = stub("PLACED", null);
    service.adminChangeStatus(b.id(), OrderStatus.CANCELLED, null, staff);
    verify(repo).setCancelInfo(b.id(), "SHOP", "CANCELLED_BY_SHOP", null);
  }

  @Test
  void rejectStoresTheRawReasonAndTheOutboxCarriesTheCode() {
    var r = stub("PLACED", null);
    service.adminReject(r.id(), " sold out ", staff);
    verify(repo).setCancelInfo(r.id(), "SHOP", "REJECTED_BY_SHOP", "sold out");
    verify(repo).insertHistory(eq(r.id()), eq("PLACED"), eq("CANCELLED"), eq(staff), eq("sold out"), any());
    @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> p = ArgumentCaptor.forClass(Map.class);
    verify(outbox).add(eq("ORDER_STATUS"), p.capture());
    assertThat(p.getValue()).containsEntry("cancelCode", "REJECTED_BY_SHOP").containsEntry("cancelReason", "sold out");
  }

  @Test
  void aNormalStatusChangeCarriesNoCancelInformation() {
    var r = stub("PLACED", null);
    service.adminChangeStatus(r.id(), OrderStatus.CONFIRMED, null, staff);
    @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> p = ArgumentCaptor.forClass(Map.class);
    verify(outbox).add(eq("ORDER_STATUS"), p.capture());
    assertThat(p.getValue()).containsEntry("cancelCode", "").containsEntry("cancelReason", "");
  }

  @Test
  void aCancelWithoutReasonSendsAnEmptyReasonInTheOutbox() {
    var r = stub("PLACED", null);
    service.customerCancel(user, r.id());
    verify(repo).setCancelInfo(r.id(), "CUSTOMER", "CUSTOMER_CANCELLED", null);
    @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> p = ArgumentCaptor.forClass(Map.class);
    verify(outbox).add(eq("ORDER_STATUS"), p.capture());
    assertThat(p.getValue()).containsEntry("cancelCode", "CUSTOMER_CANCELLED").containsEntry("cancelReason", "");
  }

  @Test
  void expiryAndPaymentFailureAreCancelledBySystem() {
    var late = stub("PAYMENT_PENDING", null);
    when(repo.expiredPending(any(), anyInt())).thenReturn(List.of(late.id()));
    service.expireUnpaid();
    verify(repo).setCancelInfo(late.id(), "SYSTEM", "PAYMENT_TIMEOUT", null);

    var failed = stub("PAYMENT_PENDING", null);
    service.on(new PaymentEvents.PaymentFailed(failed.id(), "declined"));
    verify(repo).setCancelInfo(failed.id(), "SYSTEM", "PAYMENT_FAILED", null);
  }

  @Test
  void theViewShowsCleanTextCodeAndActor() {
    var r = stub("CANCELLED", "Rejected by shop: sold out");
    OrderViews.View v = service.adminGet(r.id()).order();
    assertThat(v.cancelReason()).isEqualTo("sold out");
    assertThat(v.cancelledBy()).isEqualTo("SHOP");
    assertThat(v.cancelCode()).isEqualTo("REJECTED_BY_SHOP");
  }

  @Test
  void plainReasonOnlyStripsTheOldPrefix() {
    assertThat(OrderService.plainReason(null)).isNull();
    assertThat(OrderService.plainReason("Rejected by shop: sold out")).isEqualTo("sold out");
    assertThat(OrderService.plainReason("REJECTED BY SHOP:   closed")).isEqualTo("closed");
    assertThat(OrderService.plainReason("changed my mind")).isEqualTo("changed my mind");
    assertThat(OrderService.plainReason("rejected")).as("shorter than the prefix").isEqualTo("rejected");
  }

  @Test
  void listsCarryTheCancelFields() {
    var row = new OrderRepository.SummaryRow(UUID.randomUUID(), user, "VSM-9", "CANCELLED", "PICKUP", 100, 1, NOW, 100, null, 0, 0,
        0, 0, "COD", "NONE", "SYSTEM", "PAYMENT_TIMEOUT");
    when(repo.list(user, 0, 20)).thenReturn(new Page<>(List.of(row), 0, 20, 1));
    assertThat(service.list(user, 0, 20).items()).singleElement().satisfies(s -> {
      assertThat(s.cancelledBy()).isEqualTo("SYSTEM");
      assertThat(s.cancelCode()).isEqualTo("PAYMENT_TIMEOUT");
    });
  }
}
