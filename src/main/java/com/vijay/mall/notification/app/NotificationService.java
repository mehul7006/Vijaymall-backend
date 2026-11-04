package com.vijay.mall.notification.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.outbox.OutboxMessage;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.notification.infra.NotificationRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Turns outbox events into saved notifications and push messages. Best effort: failures are logged, not retried. */
@Service
public class NotificationService {
  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  /** One page of the person's notifications, plus how many are still unread. */
  public record Inbox(java.util.List<NotificationRepository.Row> items, int page, int size, long total, long unread) {}

  private final NotificationRepository repo;
  private final PushSender push;
  private final ProfileService profiles;
  private final JsonMapper json;
  private final Clock clock;

  public NotificationService(NotificationRepository repo, PushSender push, ProfileService profiles, JsonMapper json, Clock clock) {
    this.repo = repo;
    this.push = push;
    this.profiles = profiles;
    this.json = json;
    this.clock = clock;
  }

  @EventListener
  public void on(OutboxMessage m) {
    try {
      JsonNode p = json.readTree(m.payload());
      switch (m.type()) {
        case "ORDER_PLACED" -> {
          String no = p.path("orderNo").asString();
          notify(UUID.fromString(p.path("userId").asString()), "Order placed", "Your order " + no + " has been placed.", "ORDER", no);
          for (UUID admin : profiles.staffIds()) notify(admin, "New order", "Order " + no + " just arrived.", "ORDER", no);
        }
        case "ORDER_STATUS" -> {
          String no = p.path("orderNo").asString();
          notify(UUID.fromString(p.path("userId").asString()), "Order update", "Order " + no + " is now " + pretty(p.path("status").asString()) + ".", "ORDER", no);
        }
        default -> { }
      }
    } catch (Exception e) {
      log.error("Notification failed for {}: {}", m.type(), e.getMessage());
    }
  }

  public Inbox inbox(UUID userId, int page, int size) {
    Page<NotificationRepository.Row> p = repo.page(userId, Math.max(0, page), Page.clampSize(size));
    return new Inbox(p.items(), p.page(), p.size(), p.total(), repo.unread(userId));
  }

  /** Marks one notification read. Reading it twice is fine; someone else's is "not found". */
  public void markRead(UUID userId, UUID id) {
    if (!repo.owns(userId, id)) throw ApiException.notFound("Notification");
    repo.markRead(userId, id, clock.instant());
  }

  public void markAllRead(UUID userId) { repo.markAllRead(userId, clock.instant()); }

  private void notify(UUID userId, String title, String body, String kind, String ref) {
    repo.insert(userId, title, body, kind, ref, clock.instant());
    List<String> tokens = profiles.deviceTokens(userId);
    if (!tokens.isEmpty()) push.send(tokens, title, body);
  }

  private static String pretty(String status) { return status.toLowerCase().replace('_', ' '); }
}
