package com.vijay.mall.notification.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.outbox.OutboxMessage;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.notification.infra.NotificationRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Turns outbox events into saved notifications and push messages. Best effort: failures are logged, not retried. */
@Service
public class NotificationService {
  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  /** One page of the person's notifications, plus how many are still unread. */
  public record Inbox(List<NotificationRepository.Row> items, int page, int size, long total, long unread) {}

  private final NotificationRepository repo;
  private final PushSender push;
  private final ProfileService profiles;
  private final JsonMapper json;
  private final Clock clock;

  private CatalogService catalog;
  private InventoryService inventory;

  /** Optional (plain unit tests run without them): product names and the stock list for stock alerts. */
  @Autowired(required = false)
  public void setStockSources(CatalogService catalog, InventoryService inventory) {
    this.catalog = catalog;
    this.inventory = inventory;
  }

  /** Creates the service (wired by Spring). */
  public NotificationService(NotificationRepository repo, PushSender push, ProfileService profiles, JsonMapper json, Clock clock) {
    this.repo = repo;
    this.push = push;
    this.profiles = profiles;
    this.json = json;
    this.clock = clock;
  }

  /** Marks a note written when staff reject an order (see OrderService.adminReject). */
  public static final String REJECTED = "rejected by shop:";

  /**
   * Event listener: turns a business event from the outbox into notifications for the right people.
   * <p>
   * Handles {@code ORDER_PLACED}, {@code ORDER_STATUS}, {@code ORDER_RETURN}, {@code PAYMENT_ANOMALY}, {@code LOW_STOCK}
   * and the flash-sale events; other types are ignored. Customers hear about their own orders, staff (admins and cashiers)
   * hear about new orders, payment problems, stock and flash sales. Each notification is saved and also pushed to the
   * user's devices. Errors are logged, never thrown.
   */
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
          String note = p.path("note").asString("");
          boolean cancelled = "CANCELLED".equals(p.path("status").asString());
          if (cancelled && "REJECTED_BY_SHOP".equals(p.path("cancelCode").asString(""))) {
            notify(UUID.fromString(p.path("userId").asString()), "Order rejected", "Sorry, we could not accept order " + no + " ("
                + p.path("cancelReason").asString("") + "). Anything you paid will be refunded.", "ORDER", no);
            return;
          }
          // messages queued before cancel codes existed carry the reason in the note
          if (cancelled && note.startsWith(REJECTED)) {
            notify(UUID.fromString(p.path("userId").asString()), "Order rejected", "Sorry, we could not accept order " + no + " ("
                + note.substring(REJECTED.length()).trim() + "). Anything you paid will be refunded.", "ORDER", no);
            return;
          }
          notify(UUID.fromString(p.path("userId").asString()), "Order update", "Order " + no + " is now " + pretty(p.path("status").asString()) + ".", "ORDER", no);
        }
        case "LOW_STOCK" -> lowStock(p.path("items"));
        case "FLASH_STARTED" -> {
          int n = p.path("items").asInt();
          tellStaff("Flash sale started", "\"" + p.path("name").asString() + "\" is live now with " + n + (n == 1 ? " item." : " items."),
              p.path("saleId").asString());
        }
        case "FLASH_ENDED" -> tellStaff("Flash sale ended", "\"" + p.path("name").asString() + "\" has ended. Sold "
            + p.path("unitsSold").asLong() + " units for Rs " + rupees(p.path("revenuePaise").asLong()) + ".", p.path("saleId").asString());
        case "FLASH_ITEM_SOLD_OUT" -> {
          UUID product = UUID.fromString(p.path("productId").asString());
          String item = catalog == null ? "An item" : catalog.names(List.of(product)).getOrDefault(product, "An item");
          tellStaff("Flash sale item sold out", item + " is sold out in \"" + p.path("saleName").asString()
              + "\". It now sells at the normal price.", p.path("saleId").asString());
        }
        case "ORDER_RETURN" -> {
          String no = p.path("orderNo").asString();
          long refund = p.path("refundPaise").asLong();
          String how = switch (p.path("mode").asString("")) {
            case "ONLINE" -> "Rs " + rupees(refund) + " will go back to your original payment method.";
            case "CASH" -> "Rs " + rupees(refund) + " will be refunded by the shop.";
            default -> "The return is recorded.";
          };
          notify(UUID.fromString(p.path("userId").asString()), "Return accepted", "Order " + no + ": " + how, "ORDER", no);
        }
        case "PAYMENT_ANOMALY" -> paymentAnomaly(p);
        default -> { }
      }
    } catch (Exception e) {
      log.error("Notification failed for {}: {}", m.type(), e.getMessage());
    }
  }

  /** A wrong-amount or duplicate payment is being refunded: the customer is told what to do, the staff are told what happened. */
  private void paymentAnomaly(JsonNode p) {
    String no = p.path("orderNo").asString();
    String expected = rupees(p.path("expectedPaise").asLong());
    String received = rupees(p.path("receivedPaise").asLong());
    boolean duplicate = "DUPLICATE".equals(p.path("kind").asString());
    String customer;
    String staff;
    if (duplicate) {
      customer = "We received a second payment of Rs " + received + " for order " + no + ". It is being refunded to your original payment method.";
      staff = "Order " + no + " was paid twice. The duplicate payment of Rs " + received + " is being refunded automatically.";
    } else {
      customer = "We received Rs " + received + " instead of Rs " + expected + " for order " + no + ". It is being refunded to your original payment method."
          + (p.path("payable").asBoolean(false) ? " Please pay again." : "");
      staff = "Order " + no + ": a payment of Rs " + received + " arrived instead of Rs " + expected + ". It is being refunded automatically.";
    }
    notify(UUID.fromString(p.path("userId").asString()), duplicate ? "Duplicate payment" : "Payment amount did not match", customer, "ORDER", no);
    for (UUID admin : profiles.staffIds()) notify(admin, "Payment problem", staff, "PAYMENT", no);
  }

  /** Flash sale news goes to every staff member (admins and cashiers). */
  private void tellStaff(String title, String body, String saleId) {
    for (UUID staff : profiles.staffIds()) notify(staff, title, body, "FLASH", saleId);
  }

  private static String rupees(long paise) { return "%d.%02d".formatted(paise / 100, paise % 100); }

  private static final int NAMES_SHOWN = 3;

  /** "Rice: 0 left, Dal: 3 left, Oil: 2 left (+4 more)". */
  private static String stockLine(List<Map.Entry<String, Integer>> items) {
    String head = items.stream().limit(NAMES_SHOWN).map(e -> e.getKey() + ": " + e.getValue() + " left")
        .collect(Collectors.joining(", "));
    return items.size() > NAMES_SHOWN ? head + " (+" + (items.size() - NAMES_SHOWN) + " more)" : head;
  }

  /** Staff are told when products fall to their alert level or run out (one event per stock change). */
  private void lowStock(JsonNode items) {
    if (catalog == null) return;
    Map<UUID, Integer> qty = new LinkedHashMap<>();
    items.forEach(i -> qty.put(UUID.fromString(i.path("productId").asString()), i.path("qty").asInt()));
    Map<UUID, String> names = catalog.activeNames(qty.keySet());
    List<Map.Entry<String, Integer>> lines = qty.entrySet().stream().filter(e -> names.containsKey(e.getKey()))
        .map(e -> Map.entry(names.get(e.getKey()), e.getValue())).toList();
    if (lines.isEmpty()) return;
    boolean anyEmpty = lines.stream().anyMatch(e -> e.getValue() <= 0);
    String title = anyEmpty ? "Out of stock" : "Low stock";
    UUID first = qty.keySet().stream().filter(names::containsKey).findFirst().orElseThrow();
    for (UUID staff : profiles.staffIds()) notify(staff, title, stockLine(lines), "STOCK", first.toString());
  }

  /** Every morning (shop time) staff get one list of everything still at or below its alert level. */
  @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Kolkata")
  public void lowStockDigest() {
    if (catalog == null || inventory == null) return;
    var low = inventory.lowStock(200);
    Map<UUID, String> names = catalog.activeNames(low.stream().map(s -> s.productId()).toList());
    List<Map.Entry<String, Integer>> lines = low.stream().filter(s -> names.containsKey(s.productId()))
        .map(s -> Map.entry(names.get(s.productId()), s.qty())).toList();
    if (lines.isEmpty()) return;
    String title = lines.size() + (lines.size() == 1 ? " product needs" : " products need") + " restocking";
    for (UUID staff : profiles.staffIds()) notify(staff, title, stockLine(lines), "STOCK", null);
  }

  /**
   * A page of the user's notifications, newest first, with the unread count. The page size is clamped to the allowed maximum.
   */
  public Inbox inbox(UUID userId, int page, int size) {
    Page<NotificationRepository.Row> p = repo.page(userId, Math.max(0, page), Page.clampSize(size));
    return new Inbox(p.items(), p.page(), p.size(), p.total(), repo.unread(userId));
  }

  /** Marks one notification read. Reading it twice is fine; someone else's is "not found". */
  public void markRead(UUID userId, UUID id) {
    if (!repo.owns(userId, id)) throw ApiException.notFound("Notification");
    repo.markRead(userId, id, clock.instant());
  }

  /** Marks all of the user's notifications read. */
  public void markAllRead(UUID userId) { repo.markAllRead(userId, clock.instant()); }

  private void notify(UUID userId, String title, String body, String kind, String ref) {
    repo.insert(userId, title, body, kind, ref, clock.instant());
    List<String> tokens = profiles.deviceTokens(userId);
    if (!tokens.isEmpty()) push.send(tokens, title, body);
  }

  private static String pretty(String status) { return status.toLowerCase().replace('_', ' '); }
}
