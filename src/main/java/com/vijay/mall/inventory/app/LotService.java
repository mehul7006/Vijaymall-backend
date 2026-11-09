package com.vijay.mall.inventory.app;

import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.inventory.domain.Lot;
import com.vijay.mall.inventory.domain.LotAllocator;
import com.vijay.mall.inventory.domain.LotInfo;
import com.vijay.mall.inventory.infra.LotRepository;
import com.vijay.mall.inventory.infra.LotRepository.Allocation;
import com.vijay.mall.inventory.infra.LotRepository.Back;
import com.vijay.mall.inventory.infra.LotRepository.ProductLots;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock lots: takes units out of the right lot (oldest first, or earliest expiry first for dated goods), puts them back where they
 * came from and opens new lots, always inside the transaction of the stock change that calls it, so that the units left in a product's
 * lots add up to its stock on hand. Only {@link InventoryService} calls the changing methods.
 *
 * <p>For a product with {@code lotPriced} on, the product's selling price and MRP follow the lot that sells next; when that lot is used
 * up (or an older lot comes back) the price rolls over and the catalog-changed event tells every open screen and empties the product caches.
 */
@Service
public class LotService {
  private static final Logger log = LoggerFactory.getLogger(LotService.class);

  /** The kinds of document a taken unit can be traced to. */
  public static final String ORDER_ITEM = "ORDER_ITEM";
  /** A counter bill line. */
  public static final String POS_SALE_ITEM = "POS_SALE_ITEM";
  /** A return to the supplier. */
  public static final String PURCHASE_RETURN = "PURCHASE_RETURN";
  /** A manual correction, stock take or write-off. */
  public static final String ADJUSTMENT = "ADJUSTMENT";

  /** Units of a product wanted for one line ({@code refId}: the order or bill line, or the document). */
  public record Want(UUID productId, int qty, UUID refId) {}

  /** How a lot being opened came to be. */
  public record NewLot(UUID productId, int qty, UUID purchaseItemId, Instant receivedAt, BigDecimal unitCost, Boolean costKnown, Long mrpPaise,
                       Long pricePaise, String batchNo, LocalDate expiryDate, String source) {}

  private final LotRepository repo;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  /** Creates the service. */
  public LotService(LotRepository repo, Clock clock, ApplicationEventPublisher events) {
    this.repo = repo;
    this.clock = clock;
    this.events = events;
  }

  /** The state of one product's lots while a request is being served: what is left in memory, and what must be written. */
  private static final class Working {
    final ProductLots product;
    final List<Lot> ordered;

    Working(ProductLots product) {
      this.product = product;
      this.ordered = new ArrayList<>(product.inUseOrder());
    }
  }

  /**
   * Takes units for the wanted lines, in the order given (several lines of one product are served in that order). Stock the lots do
   * not have (stock that was never in a lot) is opened as an OPENING lot with unknown cost so a sale is never blocked by a
   * bookkeeping gap; it is logged as a warning and shows in the cost-unknown count.
   *
   * @param wants the lines
   * @param refType what the lines are (ORDER_ITEM, POS_SALE_ITEM, PURCHASE_RETURN or ADJUSTMENT)
   * @return the whole-paise cost of each line, by {@code refId}
   */
  @Transactional
  public Map<UUID, Long> take(List<Want> wants, String refType) {
    if (wants.isEmpty()) return Map.of();
    Instant now = clock.instant();
    Set<UUID> ids = new LinkedHashSet<>();
    wants.forEach(w -> ids.add(w.productId()));
    Map<UUID, ProductLots> locked = repo.lockOpen(ids);
    Map<UUID, Working> work = new LinkedHashMap<>();
    locked.forEach((id, p) -> work.put(id, new Working(p)));
    Map<UUID, Integer> decrements = new LinkedHashMap<>();
    List<Allocation> allocations = new ArrayList<>();
    Map<UUID, Long> costs = new LinkedHashMap<>();
    for (Want w : wants) {
      if (w.qty() <= 0) continue;
      Working wk = work.get(w.productId());
      List<Lot> ordered = wk == null ? new ArrayList<>() : wk.ordered;
      long cost = consume(ordered, w, refType, now, decrements, allocations);
      costs.merge(w.refId(), cost, Long::sum);
    }
    repo.decrement(decrements);
    repo.insertAllocations(allocations, now);
    syncPrices(work.values().stream().map(wk -> wk.product).toList(), work, now);
    return costs;
  }

  /** Takes one line from the ordered lots, updating them in memory; returns the line's cost. */
  private long consume(List<Lot> ordered, Want w, String refType, Instant now, Map<UUID, Integer> decrements, List<Allocation> allocations) {
    LotAllocator.Result r = LotAllocator.allocate(ordered, w.qty());
    List<LotAllocator.Take> takes = new ArrayList<>(r.takes());
    long cost = r.costPaise();
    if (r.missing() > 0) {
      log.warn("Stock without a lot: product={} missing={} ref={}; opening a lot of unknown cost", w.productId(), r.missing(), w.refId());
      Lot repair = new Lot(UUID.randomUUID(), w.productId(), now, r.missing(), r.missing(), BigDecimal.ZERO, false, null, null, "", null, "OPENING");
      repo.insertLot(repair, null, now);
      takes.add(new LotAllocator.Take(repair.id(), r.missing(), BigDecimal.ZERO, false, 0));
    }
    for (LotAllocator.Take t : takes) {
      decrements.merge(t.lotId(), t.qty(), Integer::sum);
      allocations.add(new Allocation(UUID.randomUUID(), t.lotId(), w.productId(), refType, w.refId(), t.qty(), t.unitCost(), t.costPaise(), 0, 0));
      for (int i = 0; i < ordered.size(); i++) {
        Lot l = ordered.get(i);
        if (l.id().equals(t.lotId())) ordered.set(i, l.withRemaining(l.qtyRemaining() - t.qty()));
      }
    }
    ordered.removeIf(l -> l.qtyRemaining() == 0);
    return cost;
  }

  /**
   * Takes units for a return to a supplier: first from the lots of the purchase that is being returned, then oldest first.
   *
   * @return the cost of what was taken, in whole paise
   */
  @Transactional
  public long takeForPurchaseReturn(UUID productId, int qty, UUID purchaseId, UUID returnId) {
    Instant now = clock.instant();
    Map<UUID, ProductLots> locked = repo.lockOpen(List.of(productId));
    List<Lot> first = repo.purchaseLots(purchaseId, productId);
    Set<UUID> firstIds = new LinkedHashSet<>();
    first.forEach(l -> firstIds.add(l.id()));
    List<Lot> ordered = new ArrayList<>(first);
    ProductLots product = locked.get(productId);
    if (product != null) product.inUseOrder().stream().filter(l -> !firstIds.contains(l.id())).forEach(ordered::add);
    Map<UUID, Integer> decrements = new LinkedHashMap<>();
    List<Allocation> allocations = new ArrayList<>();
    long cost = consume(ordered, new Want(productId, qty, returnId), PURCHASE_RETURN, now, decrements, allocations);
    repo.decrement(decrements);
    repo.insertAllocations(allocations, now);
    if (product != null) {
      Map<UUID, Working> work = new HashMap<>();
      Working wk = new Working(product);
      wk.ordered.clear();
      ordered.stream().filter(l -> l.productId().equals(productId)).sorted(product.trackExpiry() ? Lot.FEFO : Lot.FIFO).forEach(wk.ordered::add);
      work.put(productId, wk);
      syncPrices(List.of(product), work, now);
    }
    return cost;
  }

  /**
   * Puts units back into the lots they were taken from (by the allocation of the line), for a cancelled order or a return. Units
   * with no allocation (a sale from before lots existed) go into a new RETURN lot at the product's last known cost.
   *
   * @param wants the lines being given back; {@code refId} is the order or bill line they were taken for
   * @param refType ORDER_ITEM or POS_SALE_ITEM
   * @return the cost taken back, in whole paise, by {@code refId}
   */
  @Transactional
  public Map<UUID, Long> giveBack(List<Want> wants, String refType) {
    if (wants.isEmpty()) return Map.of();
    Instant now = clock.instant();
    List<UUID> refIds = wants.stream().map(Want::refId).filter(Objects::nonNull).distinct().toList();
    List<Allocation> open = repo.openAllocations(refType, refIds);
    Map<UUID, Integer> returned = new HashMap<>();
    List<Back> backs = new ArrayList<>();
    Map<UUID, Long> costs = new LinkedHashMap<>();
    Map<UUID, Integer> orphan = new LinkedHashMap<>();
    for (Want w : wants) {
      int left = w.qty();
      for (Allocation a : open) {
        if (left <= 0) break;
        if (!a.refId().equals(w.refId()) || !a.productId().equals(w.productId())) continue;
        int already = a.returnedQty() + returned.getOrDefault(a.id(), 0);
        int n = Math.min(left, a.qty() - already);
        if (n <= 0) continue;
        long cost = LotAllocator.returnedCost(a.costPaise(), a.qty(), already, n);
        backs.add(new Back(a.lotId(), n, a.id(), cost));
        returned.merge(a.id(), n, Integer::sum);
        costs.merge(w.refId(), cost, Long::sum);
        left -= n;
      }
      if (left > 0) orphan.merge(w.productId(), left, Integer::sum);
    }
    repo.putBack(backs, now);
    Map<UUID, BigDecimal> lastCost = repo.lastKnownCost(orphan.keySet());
    orphan.forEach((productId, qty) -> {
      BigDecimal c = lastCost.get(productId);
      repo.insertLot(new Lot(UUID.randomUUID(), productId, now, qty, qty, c == null ? BigDecimal.ZERO : c, c != null, null, null, "", null, "RETURN"), null, now);
    });
    Set<UUID> touched = new LinkedHashSet<>();
    wants.forEach(w -> touched.add(w.productId()));
    resync(touched, now);
    return costs;
  }

  /**
   * Opens a lot for received or added stock. When the unit cost is not given the product's last known cost is used (cost unknown
   * when there is none).
   */
  @Transactional
  public UUID addLot(NewLot in) {
    Instant now = clock.instant();
    BigDecimal cost = in.unitCost();
    boolean known = in.costKnown() == null || in.costKnown();
    if (cost == null) {
      cost = repo.lastKnownCost(List.of(in.productId())).get(in.productId());
      known = cost != null;
      if (cost == null) cost = BigDecimal.ZERO;
    }
    Lot lot = new Lot(UUID.randomUUID(), in.productId(), in.receivedAt() == null ? now : in.receivedAt(), in.qty(), in.qty(), cost, known, in.mrpPaise(),
        in.pricePaise(), in.batchNo() == null ? "" : in.batchNo(), in.expiryDate(), in.source());
    repo.insertLot(lot, in.purchaseItemId(), now);
    resync(Set.of(in.productId()), now);
    return lot.id();
  }

  /** Makes the price of lot-priced products follow their current lot, after lots were added or returned to. */
  private void resync(Collection<UUID> productIds, Instant now) {
    Map<UUID, ProductLots> locked = repo.lockOpen(productIds);
    Map<UUID, Working> work = new HashMap<>();
    locked.forEach((id, p) -> work.put(id, new Working(p)));
    syncPrices(new ArrayList<>(locked.values()), work, now);
  }

  /** For each lot-priced product: sets the product's price and MRP to those of the lot that sells next; announces a change. */
  private void syncPrices(Collection<ProductLots> products, Map<UUID, Working> work, Instant now) {
    boolean changed = false;
    for (ProductLots p : products) {
      if (!p.lotPriced()) continue;
      List<Lot> ordered = work.get(p.productId()).ordered;
      if (ordered.isEmpty()) continue;
      Lot current = ordered.getFirst();
      if (current.pricePaise() == null) continue;
      long mrp = current.mrpPaise() == null ? p.mrpPaise() : current.mrpPaise();
      if (repo.setProductPrice(p.productId(), current.pricePaise(), mrp, now)) {
        changed = true;
        log.info("Price rollover product={} price={} mrp={} lot={}", p.productId(), current.pricePaise(), mrp, current.id());
      }
    }
    if (changed && events != null) events.publishEvent(new CatalogChanged(CatalogChanged.CATALOG, now));
  }

  /** The lot a product's next unit comes from (empty when there is no stock), with the notice about the next price change. */
  public Optional<LotInfo> info(UUID productId, long pricePaise, long mrpPaise) {
    ProductLots p = repo.openLots(productId);
    List<Lot> ordered = p.inUseOrder();
    return LotInfo.of(ordered, p.lotPriced(), pricePaise, mrpPaise);
  }

  /** The lots in use order of the lot-priced products among these, for pricing counter lines and capping online quantities. */
  public Map<UUID, List<Lot>> pricedLots(Collection<UUID> productIds) {
    Map<UUID, List<Lot>> out = new LinkedHashMap<>();
    repo.openLotPriced(productIds).forEach((id, p) -> out.put(id, p.inUseOrder()));
    return out;
  }

  /** One lot with its stock locked until the transaction ends. */
  @Transactional
  public Optional<Lot> lockLot(UUID lotId) { return repo.lockLot(lotId); }

  /** Empties a lot that {@link #lockLot} returned: records the units as taken by an adjustment and lets a lot-priced product's price move on. */
  @Transactional
  public void writeOff(Lot lot) {
    Instant now = clock.instant();
    repo.decrement(Map.of(lot.id(), lot.qtyRemaining()));
    repo.insertAllocations(List.of(new Allocation(UUID.randomUUID(), lot.id(), lot.productId(), ADJUSTMENT, lot.id(), lot.qtyRemaining(), lot.unitCost(),
        lot.unitCost().multiply(BigDecimal.valueOf(lot.qtyRemaining())).setScale(0, RoundingMode.HALF_UP).longValueExact(), 0, 0)), now);
    resync(Set.of(lot.productId()), now);
  }
}
