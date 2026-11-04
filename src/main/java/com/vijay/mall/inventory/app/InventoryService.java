package com.vijay.mall.inventory.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.infra.InventoryRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
  public record Item(UUID productId, int qty) {}

  private final InventoryRepository repo;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  /** Without a publisher no live events are sent (used by plain unit tests). */
  public InventoryService(InventoryRepository repo, Clock clock) { this(repo, clock, null); }

  @Autowired
  public InventoryService(InventoryRepository repo, Clock clock, ApplicationEventPublisher events) {
    this.repo = repo;
    this.clock = clock;
    this.events = events;
  }

  /** Tells open screens that stock moved, once the surrounding transaction commits. */
  private void changed(List<UUID> productIds, String reason) {
    if (events != null && !productIds.isEmpty()) {
      events.publishEvent(new com.vijay.mall.common.live.StockChanged(productIds, reason, clock.instant()));
    }
  }

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

  @Transactional
  public void release(List<Item> items, UUID orderId) { release(items, orderId, "CANCEL"); }

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
  public int adjust(UUID productId, int delta, String reason, UUID actorId) {
    if (repo.add(productId, delta, clock.instant()) == 0) {
      throw ApiException.validation("delta", "stock cannot go below zero");
    }
    repo.movement(productId, delta, reason, null, actorId, clock.instant());
    changed(List.of(productId), reason);
    return repo.quantity(productId);
  }

  public void setLowThreshold(UUID productId, int low) { repo.setThreshold(productId, low); }

  public int quantity(UUID productId) { return repo.quantity(productId); }

  /** Stock rows for many products at once; products without a row count as empty. */
  public Map<UUID, InventoryRepository.Stock> stocks(java.util.Collection<UUID> productIds) {
    Map<UUID, InventoryRepository.Stock> found = repo.stocks(productIds).stream()
        .collect(java.util.stream.Collectors.toMap(InventoryRepository.Stock::productId, java.util.function.Function.identity()));
    return productIds.stream().distinct().collect(java.util.stream.Collectors.toMap(
        java.util.function.Function.identity(), id -> found.getOrDefault(id, new InventoryRepository.Stock(id, 0, 5)),
        (a, _) -> a, LinkedHashMap::new));
  }

  public List<InventoryRepository.Stock> lowStock(int limit) { return repo.lowStock(limit); }

  /** Stock history, newest first. productId null means all products. */
  public com.vijay.mall.common.Page<InventoryRepository.Movement> movements(UUID productId, int page, int size) {
    return repo.movementPage(productId, Math.max(0, page), com.vijay.mall.common.Page.clampSize(size));
  }
}
