package com.vijay.mall.inventory.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.StockChanged;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.inventory.domain.Lot;
import com.vijay.mall.inventory.domain.LotInfo;
import com.vijay.mall.inventory.infra.InventoryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
  /**
   * One product and a quantity to take or give back. {@code refId} is the order or bill line the units are for: lots record which
   * lot the units came from under it, so a cancel or a return puts them back where they came from. It may be null.
   */
  public record Item(UUID productId, int qty, UUID refId) {
    /** A line that is not tied to an order or bill line. */
    public Item(UUID productId, int qty) { this(productId, qty, null); }
  }

  /** What a bill taken after the sale happened (offline) got: the lines that came up short and what each line cost. */
  public record Taken(List<Map<String, Object>> shortfalls, Map<UUID, Long> costs) {}

  /** One delivery line received into a new lot: its paid and free units, landed unit cost, pack MRP, lot selling price, batch and expiry. */
  public record Receipt(UUID productId, int qty, UUID purchaseItemId, Instant receivedAt, BigDecimal unitCost, Long mrpPaise, Long pricePaise,
                        String batchNo, LocalDate expiryDate) {}

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
  private LotService lots;

  /** Optional (plain unit tests run without it): when present every stock change also moves units in and out of lots. */
  @Autowired(required = false)
  void setLots(LotService lots) { this.lots = lots; }

  private static String refType(String reason) {
    return switch (reason) {
      case "POS_SALE", "POS_RETURN" -> LotService.POS_SALE_ITEM;
      case "ORDER", "CANCEL", "ORDER_RETURN" -> LotService.ORDER_ITEM;
      default -> LotService.ADJUSTMENT;
    };
  }

  private static List<LotService.Want> wants(List<Item> items, UUID fallbackRef) {
    return items.stream().map(it -> new LotService.Want(it.productId(), it.qty(), it.refId() == null ? fallbackRef : it.refId())).toList();
  }

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
    alertIfLow(taken, stocks(taken.keySet()));
  }

  /** Same, from stock rows the caller has already read (after the change), so a sale reads them once. */
  private void alertIfLow(Map<UUID, Integer> taken, Map<UUID, InventoryRepository.Stock> after) {
    if (outbox == null || taken.isEmpty()) return;
    List<Map<String, Object>> crossed = new ArrayList<>();
    after.forEach((id, s) -> {
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
   * Same, and also says so when a product went out of stock or came back in, because that changes which products the shop list
   * shows: the catalog event empties the remembered product searches (and refreshes open apps) the moment the transaction commits,
   * so the shop list is never out of date. The change in quantity decides it, so a rolled-back change announces nothing.
   *
   * @param deltas how much each product's quantity changed (negative = taken)
   * @param reason why stock moved
   */
  private void changed(Map<UUID, Integer> deltas, String reason) {
    changed(deltas, reason, events == null ? Map.of() : stocks(deltas.keySet()));
  }

  /**
   * Same, from the stock rows after the change that the caller already read: ONE query for all the products of a sale, whatever
   * the number of lines.
   */
  private void changed(Map<UUID, Integer> deltas, String reason, Map<UUID, InventoryRepository.Stock> after) {
    changed(List.copyOf(deltas.keySet()), reason);
    if (events == null) return;
    boolean availabilityChanged = deltas.entrySet().stream().anyMatch(e -> {
      int now = after.get(e.getKey()).qty();
      return (now - e.getValue() > 0) != (now > 0);
    });
    if (availabilityChanged) events.publishEvent(new CatalogChanged(CatalogChanged.CATALOG, clock.instant()));
  }

  /** The stock rows after a change, read only when someone will use them (live events or low-stock alerts). */
  private Map<UUID, InventoryRepository.Stock> stocksAfter(Map<UUID, Integer> deltas) {
    return events == null && outbox == null || deltas.isEmpty() ? Map.of() : stocks(deltas.keySet());
  }

  private static Map<UUID, Integer> deltasOf(List<Item> items, int sign) {
    Map<UUID, Integer> deltas = new LinkedHashMap<>();
    items.forEach(it -> deltas.merge(it.productId(), sign * it.qty(), Integer::sum));
    return deltas;
  }

  /**
   * Creates the stock row of a new product (or resets an existing one) with the opening quantity and low-stock alert level. Writes no movement.
   */
  @Transactional
  public void create(UUID productId, int qty, int lowThreshold) {
    int before = lots == null ? 0 : repo.quantity(productId);
    repo.create(productId, qty, lowThreshold, clock.instant());
    if (lots == null || qty == before) return;
    if (qty > before) {
      lots.addLot(new LotService.NewLot(productId, qty - before, null, null, null, null, null, null, "", null, before == 0 ? "OPENING" : "ADJUSTMENT"));
    } else {
      lots.take(List.of(new LotService.Want(productId, before - qty, UUID.randomUUID())), LotService.ADJUSTMENT);
    }
  }

  /** Takes stock for an order. Items go in product-id order so two orders cannot deadlock. Returns what each line cost, by {@code Item.refId}. */
  @Transactional
  public Map<UUID, Long> reserve(List<Item> items, UUID orderId) { return reserve(items, orderId, "ORDER"); }

  /**
   * Same, recording {@code reason} (for example POS_SALE) in the stock history. The units come out of the oldest lots (earliest expiry
   * first for dated goods); the answer is the whole-paise cost of each line by {@code Item.refId} (empty when lots are not wired).
   */
  @Transactional
  public Map<UUID, Long> reserve(List<Item> items, UUID orderId, String reason) {
    List<Item> sorted = items.stream().sorted(Comparator.comparing(Item::productId)).toList();
    List<Map<String, Object>> failures = sorted.stream()
        .flatMap(it -> tryReserve(it, orderId, reason).stream())
        .toList();
    if (!failures.isEmpty()) {
      log.info("Out of stock order={} items={}", orderId, failures);
      throw ApiException.conflict("OUT_OF_STOCK", "Some items are no longer available", Map.of("items", failures));
    }
    Map<UUID, Long> costs = lots == null ? Map.of() : lots.take(wants(sorted, orderId), refType(reason));
    Map<UUID, Integer> deltas = deltasOf(items, -1);
    Map<UUID, InventoryRepository.Stock> after = stocksAfter(deltas);
    changed(deltas, reason, after);
    Map<UUID, Integer> taken = new LinkedHashMap<>();
    items.forEach(it -> taken.merge(it.productId(), it.qty(), Integer::sum));
    alertIfLow(taken, after);
    return costs;
  }

  /**
   * For a sale that has ALREADY happened (a bill rung up while the till was offline): takes what the shelf still has and
   * returns the lines that came up short, instead of refusing the bill. The goods are gone and the money is in the drawer, so
   * the sale must be recorded; the shortfall is returned for the audit trail and for the admin to look into.
   */
  @Transactional
  public List<Map<String, Object>> takeWhatIsLeft(List<Item> items, UUID orderId, String reason) {
    return takeWhatIsLeftCosted(items, orderId, reason).shortfalls();
  }

  /**
   * Same as {@link #takeWhatIsLeft}, and also says what each line cost (by {@code Item.refId}) from the lots the units came out of.
   * Several lines of one product are served in the order given.
   */
  @Transactional
  public Taken takeWhatIsLeftCosted(List<Item> items, UUID orderId, String reason) {
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
    Map<UUID, Long> costs = new LinkedHashMap<>();
    if (!taken.isEmpty()) {
      if (lots != null) costs.putAll(lots.take(splitTaken(items, taken, orderId), refType(reason)));
      Map<UUID, Integer> deltas = new LinkedHashMap<>();
      taken.forEach((id, n) -> deltas.put(id, -n));
      Map<UUID, InventoryRepository.Stock> after = stocksAfter(deltas);
      changed(deltas, reason, after);
      alertIfLow(taken, after);
    }
    return new Taken(shortfalls, costs);
  }

  /** Shares what was really taken of each product over its lines, first lines first. */
  private static List<LotService.Want> splitTaken(List<Item> items, Map<UUID, Integer> taken, UUID fallbackRef) {
    Map<UUID, Integer> left = new LinkedHashMap<>(taken);
    List<LotService.Want> out = new ArrayList<>();
    for (Item it : items.stream().sorted(Comparator.comparing(Item::productId)).toList()) {
      int n = Math.min(it.qty(), left.getOrDefault(it.productId(), 0));
      if (n <= 0) continue;
      left.merge(it.productId(), -n, Integer::sum);
      out.add(new LotService.Want(it.productId(), n, it.refId() == null ? fallbackRef : it.refId()));
    }
    return out;
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
    if (lots != null) {
      String type = refType(reason);
      if (LotService.ADJUSTMENT.equals(type)) {
        items.forEach(it -> lots.addLot(new LotService.NewLot(it.productId(), it.qty(), null, null, null, null, null, null, "", null, "RETURN")));
      } else {
        lots.giveBack(wants(items, orderId), type);
      }
    }
    changed(deltasOf(items, 1), reason);
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
    if (lots != null && delta > 0) {
      lots.addLot(new LotService.NewLot(productId, delta, null, null, null, null, null, null, "", null,
          "DEFECTIVE_REVIEW_RESTOCK".equals(reason) ? "RETURN" : "ADJUSTMENT"));
    } else if (lots != null && delta < 0) {
      lots.take(List.of(new LotService.Want(productId, -delta, refId == null ? UUID.randomUUID() : refId)), LotService.ADJUSTMENT);
    }
    changed(Map.of(productId, delta), reason);
    if (delta < 0) alertIfLow(Map.of(productId, -delta));
    return repo.quantity(productId);
  }

  /**
   * Receives a supplier delivery line: adds its units to stock (history reason PURCHASE, linked to the delivery) and opens a lot with
   * the line's landed unit cost, MRP, selling price, batch and expiry.
   *
   * @param purchaseId the delivery behind the lot
   */
  @Transactional
  public void receive(Receipt r, UUID purchaseId, UUID actorId) {
    repo.add(r.productId(), r.qty(), clock.instant());
    repo.movement(r.productId(), r.qty(), "PURCHASE", purchaseId, actorId, clock.instant());
    if (lots != null) {
      lots.addLot(new LotService.NewLot(r.productId(), r.qty(), r.purchaseItemId(), r.receivedAt(), r.unitCost(), true, r.mrpPaise(), r.pricePaise(),
          r.batchNo(), r.expiryDate(), "PURCHASE"));
    }
    changed(Map.of(r.productId(), r.qty()), "PURCHASE");
  }

  /**
   * Takes units back out for a return to the supplier: first from the lots of the purchase being returned, then oldest first.
   * Throws a validation error when less than that is on hand.
   */
  @Transactional
  public void returnToSupplier(UUID productId, int qty, UUID purchaseId, UUID returnId, UUID actorId) {
    if (repo.add(productId, -qty, clock.instant()) == 0) throw ApiException.validation("delta", "stock cannot go below zero");
    repo.movement(productId, -qty, "PURCHASE_RETURN", returnId, actorId, clock.instant());
    if (lots != null) lots.takeForPurchaseReturn(productId, qty, purchaseId, returnId);
    changed(Map.of(productId, -qty), "PURCHASE_RETURN");
    alertIfLow(Map.of(productId, qty));
  }

  /**
   * Writes the units left in one lot off the stock (for example expired goods): the lot becomes empty and the product's stock goes
   * down by the same amount, recorded as {@code EXPIRY_WRITE_OFF}.
   *
   * @return the units written off
   */
  @Transactional
  public int writeOffLot(UUID lotId, UUID actorId) {
    if (lots == null) throw new IllegalStateException("lots are not available");
    var lot = lots.lockLot(lotId).orElseThrow(() -> ApiException.notFound("Lot"));
    if (lot.qtyRemaining() == 0) throw ApiException.conflict("LOT_EMPTY", "This lot has no stock left", Map.of());
    if (repo.add(lot.productId(), -lot.qtyRemaining(), clock.instant()) == 0) {
      throw ApiException.conflict("STOCK_MISMATCH", "Stock on hand is lower than the lot; run the lot check", Map.of());
    }
    repo.movement(lot.productId(), -lot.qtyRemaining(), "EXPIRY_WRITE_OFF", lotId, actorId, clock.instant());
    lots.writeOff(lot);
    changed(Map.of(lot.productId(), -lot.qtyRemaining()), "EXPIRY_WRITE_OFF");
    alertIfLow(Map.of(lot.productId(), lot.qtyRemaining()));
    return lot.qtyRemaining();
  }

  /** The lot the next unit of a product comes from, with the price-change notice; empty without stock (or without lots). */
  public Optional<LotInfo> lotInfo(UUID productId, long pricePaise, long mrpPaise) {
    return lots == null ? Optional.empty() : lots.info(productId, pricePaise, mrpPaise);
  }

  /** The lots with stock (in the order they are sold) of the lot-priced products among these; other products are absent. */
  public Map<UUID, List<Lot>> pricedLots(Collection<UUID> productIds) {
    return lots == null ? Map.of() : lots.pricedLots(productIds);
  }

  /**
   * For lot-priced products whose stock holds more than one price: how many units can be sold at the current price (the rest of
   * the current lot and any following lots at the same price). Products with one price for all their stock are absent.
   */
  public Map<UUID, Integer> unitsAtThisPrice(Collection<UUID> productIds) {
    Map<UUID, Integer> out = new LinkedHashMap<>();
    pricedLots(productIds).forEach((id, ordered) -> LotInfo.of(ordered, true, 0, 0).map(LotInfo::nextPriceChange)
        .ifPresent(c -> out.put(id, c.afterQty())));
    return out;
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
