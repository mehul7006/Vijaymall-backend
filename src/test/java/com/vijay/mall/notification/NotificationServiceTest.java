package com.vijay.mall.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.outbox.OutboxMessage;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.notification.app.NotificationService;
import com.vijay.mall.notification.app.PushSender;
import com.vijay.mall.notification.infra.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
    verify(push).send(List.of("c-token"), "Order placed", "Your order VM-7 has been placed.");
    verify(push).send(List.of("a1", "a2"), "New order", "Order VM-7 just arrived.");
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
    verify(push).send(List.of("t"), "Order update", "Order VM-9 is now out for delivery.");
    verify(profiles, never()).staffIds();
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
    doThrow(new IllegalStateException("fcm down")).when(push).send(any(), any(), any());
    service.on(msg("ORDER_STATUS", "{\"orderNo\":\"1\",\"userId\":\"" + customer + "\",\"status\":\"PACKED\"}"));
    verify(repo).insert(eq(customer), any(), any(), any(), any(), any());
  }
}
