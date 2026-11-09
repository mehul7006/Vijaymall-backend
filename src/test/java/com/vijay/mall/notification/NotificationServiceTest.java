package com.vijay.mall.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.outbox.OutboxMessage;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository;
import com.vijay.mall.notification.app.NotificationService;
import com.vijay.mall.notification.app.PushSender;
import com.vijay.mall.notification.infra.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NotificationServiceTest {
  private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
  private final NotificationRepository repo = mock(NotificationRepository.class);
  private final PushSender push = mock(PushSender.class);
  private final ProfileService profiles = mock(ProfileService.class);
  private final NotificationService service =
      new NotificationService(repo, push, profiles, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

  private final UUID customer = UUID.randomUUID();
  private final UUID admin = UUID.randomUUID();

  private static OutboxMessage msg(String type, String payload) { return new OutboxMessage(UUID.randomUUID(), type, payload); }

  @Test
  void orderPlacedNotifiesTheCustomerAndEveryStaffMember() {
    when(profiles.staffIds()).thenReturn(List.of(admin));
    when(profiles.deviceTokens(customer)).thenReturn(List.of("c-token"));
    when(profiles.deviceTokens(admin)).thenReturn(List.of("a1", "a2"));
    service.on(msg("ORDER_PLACED", "{\"orderNo\":\"VM-7\",\"userId\":\"" + customer + "\"}"));

    verify(repo).insert(customer, "Order placed", "Your order VM-7 has been placed.", "ORDER", "VM-7", NOW);
    verify(repo).insert(admin, "New order", "Order VM-7 just arrived.", "ORDER", "VM-7", NOW);
    verify(push).send(List.of("c-token"), "Order placed", "Your order VM-7 has been placed.", Map.of("type", "ORDER", "ref", "VM-7"));
    verify(push).send(List.of("a1", "a2"), "New order", "Order VM-7 just arrived.", Map.of("type", "ORDER", "ref", "VM-7"));
  }

  @Test
  void usersWithoutDevicesGetTheInAppNotificationButNoPush() {
    when(profiles.staffIds()).thenReturn(List.of());
    when(profiles.deviceTokens(customer)).thenReturn(List.of());
    service.on(msg("ORDER_PLACED", "{\"orderNo\":\"VM-7\",\"userId\":\"" + customer + "\"}"));
    verify(repo).insert(customer, "Order placed", "Your order VM-7 has been placed.", "ORDER", "VM-7", NOW);
    verifyNoInteractions(push);
  }

  @Test
  void statusChangeTellsTheCustomerInPlainWords() {
    when(profiles.deviceTokens(customer)).thenReturn(List.of("t"));
    service.on(msg("ORDER_STATUS", "{\"orderNo\":\"VM-9\",\"userId\":\"" + customer + "\",\"status\":\"OUT_FOR_DELIVERY\"}"));
    verify(repo).insert(customer, "Order update", "Order VM-9 is now out for delivery.", "ORDER", "VM-9", NOW);
    verify(push).send(List.of("t"), "Order update", "Order VM-9 is now out for delivery.", Map.of("type", "ORDER", "ref", "VM-9"));
    verify(profiles, never()).staffIds();
  }

  @Test
  void aRejectedOrderTellsTheCustomerTheShopsReasonFromTheCancelCode() {
    when(profiles.deviceTokens(customer)).thenReturn(List.of());
    service.on(msg("ORDER_STATUS", "{\"orderNo\":\"VM-9\",\"userId\":\"" + customer
        + "\",\"status\":\"CANCELLED\",\"note\":\"sold out\",\"cancelCode\":\"REJECTED_BY_SHOP\",\"cancelReason\":\"sold out\"}"));
    verify(repo).insert(customer, "Order rejected", "Sorry, we could not accept order VM-9 (sold out). Anything you paid will be refunded.",
        "ORDER", "VM-9", NOW);
  }

  @Test
  void aRejectedOrderQueuedBeforeCancelCodesStillReadsTheReasonFromTheNote() {
    when(profiles.deviceTokens(customer)).thenReturn(List.of());
    service.on(msg("ORDER_STATUS", "{\"orderNo\":\"VM-9\",\"userId\":\"" + customer
        + "\",\"status\":\"CANCELLED\",\"note\":\"rejected by shop: closed today\"}"));
    verify(repo).insert(customer, "Order rejected", "Sorry, we could not accept order VM-9 (closed today). Anything you paid will be refunded.",
        "ORDER", "VM-9", NOW);
  }

  @Test
  void otherCancelsAreSimpleStatusUpdates() {
    when(profiles.deviceTokens(customer)).thenReturn(List.of());
    service.on(msg("ORDER_STATUS", "{\"orderNo\":\"VM-9\",\"userId\":\"" + customer
        + "\",\"status\":\"CANCELLED\",\"note\":\"payment timeout\",\"cancelCode\":\"PAYMENT_TIMEOUT\",\"cancelReason\":\"\"}"));
    verify(repo).insert(customer, "Order update", "Order VM-9 is now cancelled.", "ORDER", "VM-9", NOW);
  }

  @Test
  void unknownEventTypesAreIgnored() {
    service.on(msg("SOMETHING_ELSE", "{}"));
    verifyNoInteractions(repo, push);
  }

  @Test
  void badPayloadIsLoggedNotThrown() {
    service.on(msg("ORDER_PLACED", "not json"));
    service.on(msg("ORDER_PLACED", "{\"orderNo\":\"1\",\"userId\":\"not-a-uuid\"}"));
    verifyNoInteractions(repo, push);
  }

  @Test
  void aFailingPushSenderDoesNotEscape() {
    when(profiles.deviceTokens(customer)).thenReturn(List.of("t"));
    doThrow(new IllegalStateException("fcm down")).when(push).send(any(), any(), any(), any());
    service.on(msg("ORDER_STATUS", "{\"orderNo\":\"1\",\"userId\":\"" + customer + "\",\"status\":\"PACKED\"}"));
    verify(repo).insert(eq(customer), any(), any(), any(), any(), any());
  }

  // ---------- stock alerts ----------
  private final CatalogService catalog = mock(CatalogService.class);
  private final InventoryService inventory = mock(InventoryService.class);
  private final UUID rice = UUID.randomUUID();
  private final UUID dal = UUID.randomUUID();

  @Test
  void lowStockTellsEveryStaffMemberWhichProductsAreLow() {
    service.setStockSources(catalog, inventory);
    when(profiles.staffIds()).thenReturn(List.of(admin));
    when(catalog.activeNames(any())).thenReturn(Map.of(rice, "Rice", dal, "Dal"));
    service.on(msg("LOW_STOCK", "{\"items\":[{\"productId\":\"" + rice + "\",\"qty\":3,\"low\":5},{\"productId\":\""
        + dal + "\",\"qty\":4,\"low\":5}]}"));
    verify(repo).insert(admin, "Low stock", "Rice: 3 left, Dal: 4 left", "STOCK", rice.toString(), NOW);
  }

  @Test
  void runningOutIsCalledOutOfStockAndLongListsAreShortened() {
    service.setStockSources(catalog, inventory);
    when(profiles.staffIds()).thenReturn(List.of(admin));
    var ids = IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList();
    var names = new LinkedHashMap<UUID, String>();
    ids.forEach(id -> names.put(id, "P" + names.size()));
    when(catalog.activeNames(any())).thenReturn(names);
    String items = ids.stream().map(id -> "{\"productId\":\"" + id + "\",\"qty\":" + (id.equals(ids.get(0)) ? 0 : 2) + ",\"low\":5}")
        .collect(Collectors.joining(","));
    service.on(msg("LOW_STOCK", "{\"items\":[" + items + "]}"));
    verify(repo).insert(admin, "Out of stock", "P0: 0 left, P1: 2 left, P2: 2 left (+2 more)", "STOCK", ids.get(0).toString(), NOW);
  }

  @Test
  void retiredProductsDoNotRaiseAlerts() {
    service.setStockSources(catalog, inventory);
    when(catalog.activeNames(any())).thenReturn(Map.of());
    service.on(msg("LOW_STOCK", "{\"items\":[{\"productId\":\"" + rice + "\",\"qty\":0,\"low\":5}]}"));
    verifyNoInteractions(repo, push);
  }

  @Test
  void morningDigestListsWhatIsStillLowAndStaysQuietWhenNothingIs() {
    service.setStockSources(catalog, inventory);
    when(profiles.staffIds()).thenReturn(List.of(admin));
    when(inventory.lowStock(200)).thenReturn(List.of());
    service.lowStockDigest();
    verifyNoInteractions(repo);

    when(inventory.lowStock(200)).thenReturn(List.of(new InventoryRepository.Stock(rice, 0, 5),
        new InventoryRepository.Stock(dal, 2, 5)));
    when(catalog.activeNames(any())).thenReturn(Map.of(rice, "Rice", dal, "Dal"));
    service.lowStockDigest();
    verify(repo).insert(admin, "2 products need restocking", "Rice: 0 left, Dal: 2 left", "STOCK", null, NOW);
  }
}
