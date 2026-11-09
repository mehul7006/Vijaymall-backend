package com.vijay.mall.inventory.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.StockChanged;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.inventory.infra.InventoryRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public interface of the inventory module. Other modules never touch its tables. */
@Service
public class InventoryService {
  private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
  /** One product and a quantity to take or give back. */
  public record Item(UUID productId, int qty) {}

  private final InventoryRepository repo;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  /** Without a publisher no live events are sent (used by plain unit tests). */
  public InventoryService(InventoryRepository repo, Clock clock) { this(repo, clock, null); }

  /** Main constructor used by Spring; the publisher is used to tell open screens that stock changed. */
  @Autowired
  public InventoryService(InventoryRepository repo, Clock clock, ApplicationEventPublisher events) {
    this.repo = repo;
    this.clock = clock;
    this.events = events;
  }

  private OutboxService outbox;

  /** Optional (plain unit tests run without it): lets stock changes raise low-stock alerts. */
  @Autowired(required = false)
  void setOutbox(OutboxService outbox) { this.outbox = outbox; }

  /**
   * Raises ONE alert per product each time its stock falls onto or below its alert level (or to zero), so staff hear about
   * it once, not on every sale while it stays low. Written with the stock change, so a rolled-back sale raises nothing.
   * {@code taken} is how much each product just lost.
   */
  private void alertIfLow(Map<UUID, Integer> taken) {
    if (outbox == null || taken.isEmpty()) return;
    List<Map<String, Object>> crossed = new ArrayList<>();
    stocks(taken.keySet()).forEach((id, s) -> {
      int before = s.qty() + taken.get(id);
      boolean empty = s.qty() <= 0 && before > 0;
      boolean low = s.qty() <= s.low() && before > s.low();
      if (empty || low) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", id.toString());
        m.put("qty", s.qty());
        m.put("low", s.low());
        crossed.add(m);
      }
    });
    if (!crossed.isEmpty()) outbox.add("LOW_STOCK", Map.of("items", crossed));
  }

  /** Tells open screens that stock moved, once the surrounding transaction commits. */
  private void changed(List<UUID> productIds, String reason) {
    if (events != null && !productIds.isEmpty()) {
      events.publishEvent(new StockChanged(productIds, reason, clock.instant()));
    }
  }

  /**
   * Creates the stock row of a new product (or resets an existing one) with the opening quantity and low-stock alert level. Writes no movement.
   */
  @Transactional
  public void create(UUID productId, int qty, int lowThreshold) {
    repo.create(productId, qty, lowThreshold, clock.instant());
  }

  /** Takes stock for an order. Items go in product-id order so two orders cannot deadlock. */
  @Transactional
  public void reserve(List<Item> items, UUID orderId) { reserve(items, orderId, "ORDER"); }

  /** Same, recording {@code reason} (for example POS_SALE) in the stock history. */
  @Transactional
  public void reserve(List<Item> items, UUID orderId, String reason) {
    List<Map<String, Object>> failures = items.stream()
        .sorted(Comparator.comparing(Item::productId))
        .flatMap(it -> tryReserve(it, orderId, reason).stream())
        .toList();
    if (!failures.isEmpty()) {
      log.info("Out of stock order={} items={}", orderId, failures);
      throw ApiException.conflict("OUT_OF_STOCK", "Some items are no longer available", Map.of("items", failures));
    }
    changed(items.stream().map(Item::productId).distinct().toList(), reason);
    Map<UUID, Integer> taken = new LinkedHashMap<>();
    items.forEach(it -> taken.merge(it.productId(), it.qty(), Integer::sum));
    alertIfLow(taken);
  }

  /**
   * For a sale that has ALREADY happened (a bill rung up while the till was offline): takes what the shelf still has and
   * returns the lines that came up short, instead of refusing the bill. The goods are gone and the money is in the drawer, so
   * the sale must be recorded; the shortfall is returned for the audit trail and for the admin to look into.
   */
  @Transactional
  public List<Map<String, Object>> takeWhatIsLeft(List<Item> items, UUID orderId, String reason) {
    Map<UUID, Integer> wanted = new TreeMap<>();
    items.forEach(it -> wanted.merge(it.productId(), it.qty(), Integer::sum));
    List<Map<String, Object>> shortfalls = new ArrayList<>();
    Map<UUID, Integer> taken = new LinkedHashMap<>();
    wanted.forEach((id, qty) -> {
      int take = Math.min(qty, repo.quantity(id));
      if (take > 0 && repo.reserve(id, take, clock.instant()) > 0) {
        repo.movement(id, -take, reason, orderId, null, clock.instant());
        taken.put(id, take);
      } else {
        take = 0;
      }
      if (take < qty) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("productId", id.toString());
        s.put("sold", qty);
        s.put("taken", take);
        shortfalls.add(s);
      }
    });
    if (!taken.isEmpty()) {
      changed(List.copyOf(taken.keySet()), reason);
      alertIfLow(taken);
    }
    return shortfalls;
  }

  /** Takes one line's stock and records the movement; returns a failure description when there is not enough. */
  private Optional<Map<String, Object>> tryReserve(Item it, UUID orderId, String reason) {
    if (repo.reserve(it.productId(), it.qty(), clock.instant()) > 0) {
      repo.movement(it.productId(), -it.qty(), reason, orderId, null, clock.instant());
      return Optional.empty();
    }
    Map<String, Object> failure = new LinkedHashMap<>();
    failure.put("productId", it.productId().toString());
    failure.put("available", repo.quantity(it.productId()));
    return Optional.of(failure);
  }

  /**
   * Looks at the shelf without taking anything: throws OUT_OF_STOCK when a line asks for more than is on hand.
   * Used when an online order is placed. The stock itself is taken when staff confirm the order.
   */
  @Transactional(readOnly = true)
  public void checkAvailable(List<Item> items) {
    Map<UUID, Integer> wanted = new LinkedHashMap<>();
    items.forEach(it -> wanted.merge(it.productId(), it.qty(), Integer::sum));
    Map<UUID, InventoryRepository.Stock> have = stocks(wanted.keySet());
    List<Map<String, Object>> failures = new ArrayList<>();
    wanted.forEach((id, qty) -> {
      int available = have.get(id).qty();
      if (available < qty) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("productId", id.toString());
        failure.put("available", available);
        failures.add(failure);
      }
    });
    if (!failures.isEmpty()) throw ApiException.conflict("OUT_OF_STOCK", "Some items are no longer available", Map.of("items", failures));
  }

  /** Gives stock back for a cancelled order, recorded with the reason {@code CANCEL}. */
  @Transactional
  public void release(List<Item> items, UUID orderId) { release(items, orderId, "CANCEL"); }

  /** Gives stock back (for example for a return) and records a positive movement with the given reason. */
  @Transactional
  public void release(List<Item> items, UUID orderId, String reason) {
    items.forEach(it -> {
      repo.add(it.productId(), it.qty(), clock.instant());
      repo.movement(it.productId(), it.qty(), reason, orderId, null, clock.instant());
    });
    changed(items.stream().map(Item::productId).distinct().toList(), reason);
  }

  /** Admin restock or correction. Throws if the result would be below zero. */
  @Transactional
  public int adjust(UUID productId, int delta, String reason, UUID actorId) { return adjust(productId, delta, reason, actorId, null); }

  /** Same, linking the movement to the document behind it (for example a supplier purchase). */
  @Transactional
  public int adjust(UUID productId, int delta, String reason, UUID actorId, UUID refId) {
    if (repo.add(productId, delta, clock.instant()) == 0) {
      throw ApiException.validation("delta", "stock cannot go below zero");
    }
    repo.movement(productId, delta, reason, refId, actorId, clock.instant());
    changed(List.of(productId), reason);
    if (delta < 0) alertIfLow(Map.of(productId, -delta));
    return repo.quantity(productId);
  }

  /** Sets the quantity at or below which the product counts as low on stock. */
  public void setLowThreshold(UUID productId, int low) { repo.setThreshold(productId, low); }

  /** Units on hand; 0 for a product with no stock row. */
  public int quantity(UUID productId) { return repo.quantity(productId); }

  /** Units on hand with the stock row locked until the transaction ends, so a sale cannot slip in between a count and its adjustment. */
  public int quantityForUpdate(UUID productId) { return repo.quantityForUpdate(productId); }

  /** Stock rows for many products at once; products without a row count as empty. */
  public Map<UUID, InventoryRepository.Stock> stocks(Collection<UUID> productIds) {
    Map<UUID, InventoryRepository.Stock> found = repo.stocks(productIds).stream()
        .collect(Collectors.toMap(InventoryRepository.Stock::productId, Function.identity()));
    return productIds.stream().distinct().collect(Collectors.toMap(
        Function.identity(), id -> found.getOrDefault(id, new InventoryRepository.Stock(id, 0, 5)),
        (a, _) -> a, LinkedHashMap::new));
  }

  /** How many products are at or below their alert level (for the operations metrics). */
  public long lowStockCount() { return repo.countLow(); }

  /** Products at or below their alert level, emptiest first. Callers should drop retired products. */
  public List<InventoryRepository.Stock> lowStock(int limit) { return repo.lowStock(limit); }

  /** Stock history, newest first. productId null means all products. */
  public Page<InventoryRepository.Movement> movements(UUID productId, int page, int size) {
    return repo.movementPage(productId, Math.max(0, page), Page.clampSize(size));
  }
}
