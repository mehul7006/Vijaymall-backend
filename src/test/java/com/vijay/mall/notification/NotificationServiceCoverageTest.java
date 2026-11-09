package com.vijay.mall.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.outbox.OutboxMessage;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository.Stock;
import com.vijay.mall.notification.app.NotificationService;
import com.vijay.mall.notification.app.PushSender;
import com.vijay.mall.notification.infra.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

class NotificationServiceCoverageTest {
  private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
  private final NotificationRepository repo = mock(NotificationRepository.class);
  private final ProfileService profiles = mock(ProfileService.class);
  private final CatalogService catalog = mock(CatalogService.class);
  private final InventoryService inventory = mock(InventoryService.class);
  private final NotificationService service = new NotificationService(repo, mock(PushSender.class), profiles,
      JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID rice = UUID.randomUUID();
  private final UUID admin = UUID.randomUUID();

  @Test void lowStockEventWithoutStockSources_isIgnored() {
    service.on(new OutboxMessage(UUID.randomUUID(), "LOW_STOCK",
        "{\"items\":[{\"productId\":\"" + rice + "\",\"qty\":0,\"low\":5}]}"));
    verifyNoInteractions(repo);
  }

  @Test void digestWithoutCatalog_orWithoutInventory_doesNothing() {
    service.lowStockDigest(); // neither wired
    service.setStockSources(catalog, null);
    service.lowStockDigest();
    service.setStockSources(null, inventory);
    service.lowStockDigest();
    verifyNoInteractions(repo, inventory, catalog);
  }

  @Test void digestWithOneProduct_usesTheSingularTitle() {
    service.setStockSources(catalog, inventory);
    when(profiles.staffIds()).thenReturn(List.of(admin));
    when(inventory.lowStock(200)).thenReturn(List.of(new Stock(rice, 1, 5)));
    when(catalog.activeNames(any())).thenReturn(Map.of(rice, "Rice"));
    service.lowStockDigest();
    verify(repo).insert(admin, "1 product needs restocking", "Rice: 1 left", "STOCK", null, NOW);
  }

  @Test void digestSkipsRetiredProducts() {
    service.setStockSources(catalog, inventory);
    when(profiles.staffIds()).thenReturn(List.of(admin));
    when(inventory.lowStock(200)).thenReturn(List.of(new Stock(rice, 1, 5), new Stock(UUID.randomUUID(), 0, 5)));
    when(catalog.activeNames(any())).thenReturn(Map.of(rice, "Rice"));
    service.lowStockDigest();
    verify(repo).insert(admin, "1 product needs restocking", "Rice: 1 left", "STOCK", null, NOW);
  }

  private void on(String type, String payload) { service.on(new OutboxMessage(UUID.randomUUID(), type, payload)); }

  @Test void flashStartedWithOneItem_usesTheSingularWord() {
    when(profiles.staffIds()).thenReturn(List.of(admin));
    on("FLASH_STARTED", "{\"saleId\":\"s1\",\"name\":\"Diwali\",\"items\":1}");
    on("FLASH_STARTED", "{\"saleId\":\"s1\",\"name\":\"Diwali\",\"items\":3}");
    verify(repo).insert(admin, "Flash sale started", "\"Diwali\" is live now with 1 item.", "FLASH", "s1", NOW);
    verify(repo).insert(admin, "Flash sale started", "\"Diwali\" is live now with 3 items.", "FLASH", "s1", NOW);
  }

  @Test void soldOutFlashItem_isNamedWhenTheCatalogKnowsIt_andGenericOtherwise() {
    when(profiles.staffIds()).thenReturn(List.of(admin));
    String payload = "{\"saleId\":\"s1\",\"saleName\":\"Diwali\",\"productId\":\"" + rice + "\"}";
    on("FLASH_ITEM_SOLD_OUT", payload); // no catalog wired yet
    verify(repo).insert(admin, "Flash sale item sold out", "An item is sold out in \"Diwali\". It now sells at the normal price.", "FLASH", "s1", NOW);

    service.setStockSources(catalog, inventory);
    when(catalog.names(List.of(rice))).thenReturn(Map.of(rice, "Rice"));
    on("FLASH_ITEM_SOLD_OUT", payload);
    verify(repo).insert(admin, "Flash sale item sold out", "Rice is sold out in \"Diwali\". It now sells at the normal price.", "FLASH", "s1", NOW);

    when(catalog.names(List.of(rice))).thenReturn(Map.of());
    Mockito.clearInvocations(repo);
    on("FLASH_ITEM_SOLD_OUT", payload);
    verify(repo).insert(admin, "Flash sale item sold out", "An item is sold out in \"Diwali\". It now sells at the normal price.", "FLASH", "s1", NOW);
  }

  @Test void returnWithoutARefundMode_isJustRecorded() {
    UUID user = UUID.randomUUID();
    on("ORDER_RETURN", "{\"orderNo\":\"VSM-1\",\"userId\":\"" + user + "\",\"refundPaise\":0,\"mode\":\"NONE\"}");
    verify(repo).insert(user, "Return accepted", "Order VSM-1: The return is recorded.", "ORDER", "VSM-1", NOW);
    on("ORDER_RETURN", "{\"orderNo\":\"VSM-2\",\"userId\":\"" + user + "\",\"refundPaise\":12050,\"mode\":\"CASH\"}");
    verify(repo).insert(user, "Return accepted", "Order VSM-2: Rs 120.50 will be refunded by the shop.", "ORDER", "VSM-2", NOW);
    on("ORDER_RETURN", "{\"orderNo\":\"VSM-4\",\"userId\":\"" + user + "\",\"refundPaise\":500,\"mode\":\"ONLINE\"}");
    verify(repo).insert(user, "Return accepted", "Order VSM-4: Rs 5.00 will go back to your original payment method.", "ORDER", "VSM-4", NOW);
    on("ORDER_RETURN", "{\"orderNo\":\"VSM-3\",\"userId\":\"" + user + "\",\"refundPaise\":500}");
    verify(repo).insert(user, "Return accepted", "Order VSM-3: The return is recorded.", "ORDER", "VSM-3", NOW);
  }

  @Test void wrongAmountPayment_tellsTheCustomerToPayAgainAndTheStaffWhatHappened() {
    UUID user = UUID.randomUUID();
    when(profiles.staffIds()).thenReturn(List.of(admin));
    on("PAYMENT_ANOMALY", "{\"orderNo\":\"VSM-5\",\"userId\":\"" + user
        + "\",\"kind\":\"MISMATCH\",\"expectedPaise\":50000,\"receivedPaise\":40050,\"payable\":true}");
    verify(repo).insert(user, "Payment amount did not match", "We received Rs 400.50 instead of Rs 500.00 for order VSM-5. "
        + "It is being refunded to your original payment method. Please pay again.", "ORDER", "VSM-5", NOW);
    verify(repo).insert(admin, "Payment problem", "Order VSM-5: a payment of Rs 400.50 arrived instead of Rs 500.00. "
        + "It is being refunded automatically.", "PAYMENT", "VSM-5", NOW);
  }

  @Test void wrongAmountOnAnOrderThatCannotBePaidAnymore_doesNotAskToPayAgain() {
    UUID user = UUID.randomUUID();
    when(profiles.staffIds()).thenReturn(List.of());
    on("PAYMENT_ANOMALY", "{\"orderNo\":\"VSM-6\",\"userId\":\"" + user
        + "\",\"kind\":\"MISMATCH\",\"expectedPaise\":100,\"receivedPaise\":99}");
    verify(repo).insert(user, "Payment amount did not match", "We received Rs 0.99 instead of Rs 1.00 for order VSM-6. "
        + "It is being refunded to your original payment method.", "ORDER", "VSM-6", NOW);
  }

  @Test void duplicatePayment_isExplainedToTheCustomerAndTheStaff() {
    UUID user = UUID.randomUUID();
    when(profiles.staffIds()).thenReturn(List.of(admin));
    on("PAYMENT_ANOMALY", "{\"orderNo\":\"VSM-7\",\"userId\":\"" + user
        + "\",\"kind\":\"DUPLICATE\",\"expectedPaise\":2500,\"receivedPaise\":2500,\"payable\":false}");
    verify(repo).insert(user, "Duplicate payment", "We received a second payment of Rs 25.00 for order VSM-7. "
        + "It is being refunded to your original payment method.", "ORDER", "VSM-7", NOW);
    verify(repo).insert(admin, "Payment problem", "Order VSM-7 was paid twice. The duplicate payment of Rs 25.00 is being refunded automatically.",
        "PAYMENT", "VSM-7", NOW);
  }
}
