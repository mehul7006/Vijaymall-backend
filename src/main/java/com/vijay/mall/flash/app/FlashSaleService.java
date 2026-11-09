package com.vijay.mall.flash.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.FlashPricing;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.flash.app.FlashViews.ItemView;
import com.vijay.mall.flash.app.FlashViews.SaleSummary;
import com.vijay.mall.flash.app.FlashViews.SaleView;
import com.vijay.mall.flash.app.FlashViews.ShopItem;
import com.vijay.mall.flash.app.FlashViews.ShopSale;
import com.vijay.mall.flash.app.FlashViews.ShopSales;
import com.vijay.mall.flash.domain.FlashKind;
import com.vijay.mall.flash.domain.FlashMath;
import com.vijay.mall.flash.domain.FlashStatus;
import com.vijay.mall.flash.domain.FlashWindow;
import com.vijay.mall.flash.infra.FlashRepository.Item;
import com.vijay.mall.flash.infra.FlashRepository.Sale;
import com.vijay.mall.flash.infra.FlashRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff configure flash sales here (create, edit, items, start now, end now, cancel, duplicate); customers read the running and
 * coming sales. Rules in short: a product may be in only one running-or-scheduled sale at a time; once a unit of an item has sold,
 * only its quantity may be raised; a running sale can only be renamed or have its end moved.
 */
@Service
public class FlashSaleService {
  private static final Logger log = LoggerFactory.getLogger(FlashSaleService.class);

  /** Most products allowed in one sale. */
  public static final int MAX_ITEMS = 200;
  /** Longest sale name or banner text, in characters. */
  public static final int MAX_NAME = 80;
  /** Longest sale description, in characters. */
  public static final int MAX_DESCRIPTION = 300;
  /** Largest cap on units of one item. */
  public static final int MAX_QTY = 1_000_000;
  /** Largest per-customer limit; 0 means no limit. */
  public static final int MAX_CUSTOMER_LIMIT = 999;

  /** discountKind/discountValue/maxQty/perCustomerLimit may be null: follow the sale, no cap, follow the sale. */
  @Schema(name = "FlashItemInput")
  public record ItemInput(UUID productId, String discountKind, Long discountValue, Integer maxQty, Integer perCustomerLimit) {}

  /** startsAt null = start now. channel null = BOTH. A null discountKind means NONE (every item then needs its own discount). */
  public record CreateInput(String name, String description, String bannerText, Instant startsAt, Instant endsAt, String channel,
                            String discountKind, Long discountValue, Integer perCustomerLimit, List<ItemInput> items) {}

  /** Only the fields that are not null change. */
  public record UpdateInput(String name, String description, String bannerText, Instant startsAt, Instant endsAt, String channel,
                            String discountKind, Long discountValue, Integer perCustomerLimit) {}

  /** A copy of a sale on a new schedule. startsAt null = now; endsAt null = same length as the original. */
  public record DuplicateInput(String name, Instant startsAt, Instant endsAt) {}

  private final FlashRepository repo;
  private final CatalogService catalog;
  private final AuditService audit;
  private final LiveEvents live;
  private final Clock clock;
  private final FlashPricingService pricing;

  /** Creates the service. */
  public FlashSaleService(FlashRepository repo, CatalogService catalog, AuditService audit, LiveEvents live, Clock clock,
                          FlashPricingService pricing) {
    this.repo = repo;
    this.catalog = catalog;
    this.audit = audit;
    this.live = live;
    this.clock = clock;
    this.pricing = pricing;
  }

  // ---------- create ----------
  /**
   * Creates a sale with its items, after checking the name, schedule, discounts, products and overlaps.
   * <p>Takes the schedule lock so two staff members cannot put the same product in overlapping sales.
   * @param in the new sale; {@code startsAt} null starts it now
   * @param actor the staff member creating it
   * @return the saved sale
   * @throws ApiException validation errors, or conflict {@code FLASH_OVERLAP}
   */
  @Transactional
  public SaleView create(CreateInput in, UUID actor) {
    Instant now = clock.instant();
    repo.lockSchedule();
    String name = name(in.name());
    FlashKind kind = kind("discountKind", in.discountKind());
    long value = ruleValue("discountValue", kind, in.discountValue());
    List<ItemInput> items = in.items() == null ? List.of() : in.items();
    if (items.isEmpty()) throw ApiException.validation("items", "choose at least one product");
    if (items.size() > MAX_ITEMS) throw ApiException.validation("items", "at most " + MAX_ITEMS + " products in one sale");
    Instant start = in.startsAt() == null ? now : in.startsAt();
    window(start, in.endsAt(), now, false);
    Sale sale = new Sale(UUID.randomUUID(), name, text("description", in.description(), MAX_DESCRIPTION), text("bannerText", in.bannerText(), MAX_NAME),
        start, in.endsAt(), channel(in.channel()), kind.name(), value, limit("perCustomerLimit", in.perCustomerLimit(), 0), null, actor,
        now, now);
    List<Item> built = buildItems(sale, items, null);
    requireNoClash(built.stream().map(Item::productId).toList(), sale.startsAt(), sale.endsAt(), new UUID(0, 0));
    repo.insertSale(sale);
    built.forEach(i -> repo.insertItem(i, now));
    audit.log(actor, "FLASH_CREATE", "flash_sale", sale.id(), sale.name() + " " + sale.startsAt() + " -> " + sale.endsAt() + " items=" + built.size());
    logEvent("flash-created", sale, actor).addKeyValue("items", built.size()).log();
    changed();
    return get(sale.id());
  }

  private List<Item> buildItems(Sale sale, List<ItemInput> inputs, UUID exceptSale) {
    Set<UUID> seen = new HashSet<>();
    for (ItemInput i : inputs) {
      if (i.productId() == null) throw ApiException.validation("items", "every item needs a productId");
      if (!seen.add(i.productId())) throw ApiException.validation("items", "a product can be added only once: " + i.productId());
    }
    Map<UUID, ProductData> products = productsById(seen);
    List<Item> out = new ArrayList<>();
    for (ItemInput i : inputs) out.add(buildItem(sale, i, products.get(i.productId()), UUID.randomUUID()));
    return out;
  }

  private Item buildItem(Sale sale, ItemInput i, ProductData p, UUID id) {
    if (p == null) throw ApiException.validation("items", "unknown product " + i.productId());
    if (!p.active()) throw ApiException.validation("items", p.nameEn() + " is not active");
    String itemKind = null;
    long itemValue = 0;
    if (i.discountKind() != null && !i.discountKind().isBlank()) {
      FlashKind k = kind("items", i.discountKind());
      if (k != FlashKind.NONE) {
        itemKind = k.name();
        itemValue = ruleValue("items", k, i.discountValue());
      }
    }
    FlashMath.Rule rule = FlashMath.effective(itemKind == null ? null : FlashKind.valueOf(itemKind), itemValue,
        FlashKind.valueOf(sale.discountKind()), sale.discountValue());
    if (!rule.usable()) throw ApiException.validation("items", p.nameEn() + " needs its own discount (the sale has no default discount)");
    if (!FlashMath.fixedPriceBelow(rule, p.pricePaise()) || FlashMath.salePrice(rule, p.pricePaise()).isEmpty()) {
      throw ApiException.validation("items", p.nameEn() + ": this discount gives no lower price than its current price of Rs "
          + rupees(p.pricePaise()));
    }
    Integer max = i.maxQty();
    if (max != null && (max < 1 || max > MAX_QTY)) throw ApiException.validation("items", "maxQty must be 1 to " + MAX_QTY);
    Integer cust = i.perCustomerLimit();
    if (cust != null && (cust < 0 || cust > MAX_CUSTOMER_LIMIT)) {
      throw ApiException.validation("items", "perCustomerLimit must be 0 to " + MAX_CUSTOMER_LIMIT);
    }
    return new Item(id, sale.id(), p.id(), itemKind, itemValue, max, 0, cust);
  }

  // ---------- update ----------
  /**
   * Changes the given fields of a sale that has not ended. A running sale can only be renamed, re-described or have its end moved.
   * @throws ApiException conflict {@code FLASH_RUNNING}, {@code FLASH_NOT_ALLOWED} (ended or cancelled) or {@code FLASH_OVERLAP}, or validation errors
   */
  @Transactional
  public SaleView update(UUID id, UpdateInput in, UUID actor) {
    Instant now = clock.instant();
    repo.lockSchedule();
    Sale s = sale(id);
    FlashStatus status = status(s, now);
    requireOpen(status);
    List<Item> items = repo.items(id);
    String name = in.name() == null ? s.name() : name(in.name());
    String description = in.description() == null ? s.description() : text("description", in.description(), MAX_DESCRIPTION);
    String banner = in.bannerText() == null ? s.bannerText() : text("bannerText", in.bannerText(), MAX_NAME);
    Instant start = in.startsAt() == null ? s.startsAt() : in.startsAt();
    Instant end = in.endsAt() == null ? s.endsAt() : in.endsAt();
    String channel = in.channel() == null ? s.channel() : channel(in.channel());
    FlashKind kind = in.discountKind() == null ? FlashKind.valueOf(s.discountKind()) : kind("discountKind", in.discountKind());
    long value = in.discountKind() == null && in.discountValue() == null ? s.discountValue()
        : ruleValue("discountValue", kind, in.discountValue() == null ? s.discountValue() : in.discountValue());
    int custLimit = in.perCustomerLimit() == null ? s.perCustomerLimit() : limit("perCustomerLimit", in.perCustomerLimit(), 0);
    if (status == FlashStatus.ACTIVE) {
      boolean touchesRules = !start.equals(s.startsAt()) || !channel.equals(s.channel()) || kind != FlashKind.valueOf(s.discountKind())
          || value != s.discountValue() || custLimit != s.perCustomerLimit();
      if (touchesRules) {
        throw ApiException.conflict("FLASH_RUNNING", "A running sale can only be renamed or have its end time changed", Map.of());
      }
    }
    window(start, end, now, status == FlashStatus.ACTIVE);
    Sale changed = new Sale(id, name, description, banner, start, end, channel, kind.name(), value, custLimit, null, s.createdBy(),
        s.createdAt(), now);
    if (kind != FlashKind.valueOf(s.discountKind()) || value != s.discountValue()) revalidate(changed, items);
    if (!start.equals(s.startsAt()) || !end.equals(s.endsAt())) {
      requireNoClash(items.stream().map(Item::productId).toList(), start, end, id);
    }
    repo.updateSale(changed);
    audit.log(actor, "FLASH_UPDATE", "flash_sale", id, change(s, changed));
    logEvent("flash-updated", changed, actor).addKeyValue("change", change(s, changed)).log();
    changed();
    return get(id);
  }

  /** After the sale's default discount changed, every item must still end up with a valid discount. */
  private void revalidate(Sale sale, List<Item> items) {
    Map<UUID, ProductData> products = productsById(items.stream().map(Item::productId).collect(Collectors.toSet()));
    for (Item i : items) {
      buildItem(sale, new ItemInput(i.productId(), i.discountKind(), i.discountValue(), i.maxQty(), i.perCustomerLimit()),
          products.get(i.productId()), i.id());
    }
  }

  private static String change(Sale a, Sale b) {
    List<String> parts = new ArrayList<>();
    if (!a.name().equals(b.name())) parts.add("name '" + a.name() + "' -> '" + b.name() + "'");
    if (!a.description().equals(b.description())) parts.add("description");
    if (!a.bannerText().equals(b.bannerText())) parts.add("bannerText");
    if (!a.startsAt().equals(b.startsAt())) parts.add("start " + a.startsAt() + " -> " + b.startsAt());
    if (!a.endsAt().equals(b.endsAt())) parts.add("end " + a.endsAt() + " -> " + b.endsAt());
    if (!a.channel().equals(b.channel())) parts.add("channel " + a.channel() + " -> " + b.channel());
    if (!a.discountKind().equals(b.discountKind()) || a.discountValue() != b.discountValue()) {
      parts.add("discount " + a.discountKind() + " " + a.discountValue() + " -> " + b.discountKind() + " " + b.discountValue());
    }
    if (a.perCustomerLimit() != b.perCustomerLimit()) parts.add("perCustomerLimit " + a.perCustomerLimit() + " -> " + b.perCustomerLimit());
    return parts.isEmpty() ? "no change" : String.join("; ", parts);
  }

  // ---------- items ----------
  /**
   * Adds one product to a sale that has not ended; rejects duplicates, inactive products, a discount that gives no lower price, and overlaps with other sales.
   */
  @Transactional
  public SaleView addItem(UUID saleId, ItemInput in, UUID actor) {
    Instant now = clock.instant();
    repo.lockSchedule();
    Sale s = sale(saleId);
    requireOpen(status(s, now));
    List<Item> items = repo.items(saleId);
    if (items.size() >= MAX_ITEMS) throw ApiException.validation("items", "at most " + MAX_ITEMS + " products in one sale");
    if (in.productId() != null && items.stream().anyMatch(i -> i.productId().equals(in.productId()))) {
      throw ApiException.validation("items", "this product is already in the sale");
    }
    Item item = buildItem(s, in, in.productId() == null ? null : productsById(Set.of(in.productId())).get(in.productId()), UUID.randomUUID());
    requireNoClash(List.of(item.productId()), s.startsAt(), s.endsAt(), saleId);
    repo.insertItem(item, now);
    touch(s, now);
    audit.log(actor, "FLASH_ITEM_ADD", "flash_sale", saleId, "product " + item.productId());
    logEvent("flash-updated", s, actor).addKeyValue("change", "item added " + item.productId()).log();
    changed();
    return get(saleId);
  }

  /**
   * Removes an item that has never sold; throws conflict {@code FLASH_ITEM_SOLD} when units were sold or the item has history.
   */
  @Transactional
  public SaleView removeItem(UUID saleId, UUID itemId, UUID actor) {
    Instant now = clock.instant();
    Sale s = sale(saleId);
    requireOpen(status(s, now));
    Item item = item(saleId, itemId);
    if (item.soldQty() > 0 || repo.hasHistory(itemId)) {
      throw ApiException.conflict("FLASH_ITEM_SOLD", "Units of this item have already been sold, so it cannot be removed. Cancel or end the sale instead.",
          Map.of("soldQty", item.soldQty()));
    }
    repo.deleteItem(itemId);
    touch(s, now);
    audit.log(actor, "FLASH_ITEM_REMOVE", "flash_sale", saleId, "product " + item.productId());
    logEvent("flash-updated", s, actor).addKeyValue("change", "item removed " + item.productId()).log();
    changed();
    return get(saleId);
  }

  /** Replaces the item's settings. Once a unit has sold, only the quantity cap may change, and only upwards. */
  @Transactional
  public SaleView updateItem(UUID saleId, UUID itemId, ItemInput in, UUID actor) {
    Instant now = clock.instant();
    repo.lockSchedule();
    Sale s = sale(saleId);
    requireOpen(status(s, now));
    Item old = item(saleId, itemId);
    Item built = buildItem(s, new ItemInput(old.productId(), in.discountKind(), in.discountValue(), in.maxQty(), in.perCustomerLimit()),
        productsById(Set.of(old.productId())).get(old.productId()), itemId);
    if (old.soldQty() > 0 || repo.hasHistory(itemId)) {
      boolean sameRest = Objects.equals(old.discountKind(), built.discountKind()) && old.discountValue() == built.discountValue()
          && Objects.equals(old.perCustomerLimit(), built.perCustomerLimit());
      if (!sameRest) {
        throw ApiException.conflict("FLASH_ITEM_SOLD", "Units of this item have already been sold: only the quantity can be raised", Map.of());
      }
      if (old.maxQty() == null ? built.maxQty() != null : built.maxQty() != null && built.maxQty() < old.maxQty()) {
        throw ApiException.conflict("FLASH_ITEM_SOLD", "Units of this item have already been sold: the quantity can only be raised", Map.of());
      }
    }
    repo.updateItem(built);
    touch(s, now);
    audit.log(actor, "FLASH_ITEM_UPDATE", "flash_sale", saleId, "product " + old.productId() + " max " + old.maxQty() + " -> " + built.maxQty());
    logEvent("flash-updated", s, actor).addKeyValue("change", "item changed " + old.productId()).log();
    changed();
    return get(saleId);
  }

  // ---------- start / end / cancel / duplicate ----------
  /**
   * Moves a scheduled sale's start to now. Throws {@code FLASH_NOT_ALLOWED} unless it is scheduled, and {@code FLASH_OVERLAP} if that would clash with another sale.
   */
  @Transactional
  public SaleView startNow(UUID id, UUID actor) {
    Instant now = clock.instant();
    repo.lockSchedule();
    Sale s = sale(id);
    FlashStatus st = status(s, now);
    if (st != FlashStatus.SCHEDULED) throw notAllowed("Only a scheduled sale can be started now (this one is " + st + ")");
    if (!s.endsAt().isAfter(now)) throw ApiException.validation("endsAt", "the end time has already passed");
    requireNoClash(repo.items(id).stream().map(Item::productId).toList(), now, s.endsAt(), id);
    Sale started = withTimes(s, now, s.endsAt(), now);
    repo.updateSale(started);
    audit.log(actor, "FLASH_START", "flash_sale", id, s.name() + " moved from " + s.startsAt());
    logEvent("flash-updated", started, actor).addKeyValue("change", "start-now").log();
    changed();
    return get(id);
  }

  /** Moves a running sale's end to now. Throws {@code FLASH_NOT_ALLOWED} unless it is running. */
  @Transactional
  public SaleView endNow(UUID id, UUID actor) {
    Instant now = clock.instant();
    Sale s = sale(id);
    FlashStatus st = status(s, now);
    if (st != FlashStatus.ACTIVE) throw notAllowed("Only a running sale can be ended now (this one is " + st + ")");
    Sale ended = withTimes(s, s.startsAt(), now, now);
    repo.updateSale(ended);
    audit.log(actor, "FLASH_END", "flash_sale", id, s.name() + " ended early, was due " + s.endsAt());
    logEvent("flash-updated", ended, actor).addKeyValue("change", "end-now").log();
    changed();
    return get(id);
  }

  /**
   * Cancels a scheduled or running sale (an ended or cancelled one throws {@code FLASH_NOT_ALLOWED}). Units already sold keep their price.
   */
  @Transactional
  public SaleView cancel(UUID id, UUID actor) {
    Instant now = clock.instant();
    Sale s = sale(id);
    FlashStatus st = status(s, now);
    requireOpen(st);
    repo.updateSale(new Sale(id, s.name(), s.description(), s.bannerText(), s.startsAt(), s.endsAt(), s.channel(), s.discountKind(),
        s.discountValue(), s.perCustomerLimit(), now, s.createdBy(), s.createdAt(), now));
    audit.log(actor, "FLASH_CANCEL", "flash_sale", id, s.name() + " was " + st);
    logEvent("flash-cancelled", s, actor).addKeyValue("wasStatus", st).log();
    changed();
    return get(id);
  }

  /**
   * Copies a sale and its items (sold counts reset) onto a new schedule.
   * @param in optional new name, start and end; start defaults to now and the length to the original's, the name to the original with " (copy)" added
   */
  @Transactional
  public SaleView duplicate(UUID id, DuplicateInput in, UUID actor) {
    Instant now = clock.instant();
    repo.lockSchedule();
    Sale s = sale(id);
    DuplicateInput req = in == null ? new DuplicateInput(null, null, null) : in;
    Instant start = req.startsAt() == null ? now : req.startsAt();
    Instant end = req.endsAt() == null ? start.plus(Duration.between(s.startsAt(), s.endsAt())) : req.endsAt();
    window(start, end, now, false);
    List<Item> copies = repo.items(id).stream()
        .map(i -> new Item(UUID.randomUUID(), null, i.productId(), i.discountKind(), i.discountValue(), i.maxQty(), 0, i.perCustomerLimit()))
        .toList();
    Sale copy = new Sale(UUID.randomUUID(), req.name() == null ? name(s.name().length() > MAX_NAME - 7 ? s.name().substring(0, MAX_NAME - 7) + " (copy)"
        : s.name() + " (copy)") : name(req.name()), s.description(), s.bannerText(), start, end, s.channel(), s.discountKind(),
        s.discountValue(), s.perCustomerLimit(), null, actor, now, now);
    Map<UUID, ProductData> products = productsById(copies.stream().map(Item::productId).collect(Collectors.toSet()));
    List<Item> built = copies.stream().map(i -> buildItem(copy, new ItemInput(i.productId(), i.discountKind(), i.discountValue(),
        i.maxQty(), i.perCustomerLimit()), products.get(i.productId()), i.id())).toList();
    requireNoClash(built.stream().map(Item::productId).toList(), start, end, new UUID(0, 0));
    repo.insertSale(copy);
    built.forEach(i -> repo.insertItem(new Item(i.id(), copy.id(), i.productId(), i.discountKind(), i.discountValue(), i.maxQty(), 0,
        i.perCustomerLimit()), now));
    audit.log(actor, "FLASH_DUPLICATE", "flash_sale", copy.id(), "copy of " + id);
    logEvent("flash-created", copy, actor).addKeyValue("copyOf", id).addKeyValue("items", built.size()).log();
    changed();
    return get(copy.id());
  }

  // ---------- read (staff) ----------
  /** One sale with its items and their current sale prices; {@code NOT_FOUND} when unknown. */
  @Transactional(readOnly = true)
  public SaleView get(UUID id) {
    Sale s = sale(id);
    return view(s, repo.items(id), clock.instant());
  }

  /** status null = all. Newest start first. */
  @Transactional(readOnly = true)
  public Page<SaleSummary> list(String status, int page, int size) {
    String st = null;
    if (status != null && !status.isBlank()) {
      st = status.trim().toUpperCase(Locale.ROOT);
      try {
        FlashStatus.valueOf(st);
      } catch (IllegalArgumentException e) {
        throw ApiException.validation("status", "must be SCHEDULED, ACTIVE, ENDED or CANCELLED");
      }
    }
    Instant now = clock.instant();
    int sz = Page.clampSize(size);
    int pg = Math.max(0, page);
    List<Sale> sales = repo.list(st, now, sz, pg * sz);
    Map<UUID, Integer> counts = new LinkedHashMap<>();
    sales.forEach(s -> counts.put(s.id(), repo.items(s.id()).size()));
    return new Page<>(sales.stream().map(s -> new SaleSummary(s.id(), s.name(), status(s, now).name(), s.startsAt(), s.endsAt(), s.channel(),
        counts.get(s.id()))).toList(), pg, sz, repo.count(st, now));
  }

  private SaleView view(Sale s, List<Item> items, Instant now) {
    Map<UUID, ProductData> products = productsById(items.stream().map(Item::productId).collect(Collectors.toSet()));
    FlashKind saleKind = FlashKind.valueOf(s.discountKind());
    List<ItemView> views = items.stream().map(i -> {
      ProductData p = products.get(i.productId());
      long list = p == null ? 0 : p.pricePaise();
      FlashMath.Rule rule = FlashMath.effective(i.discountKind() == null ? null : FlashKind.valueOf(i.discountKind()), i.discountValue(),
          saleKind, s.discountValue());
      Long sale = FlashMath.salePrice(rule, list).orElse(null);
      int effLimit = i.perCustomerLimit() != null ? i.perCustomerLimit() : s.perCustomerLimit();
      return new ItemView(i.id(), i.productId(), p == null ? "?" : p.nameEn(), list, sale, sale == null ? null : FlashMath.discountPercent(list, sale),
          i.discountKind(), i.discountValue(), rule.kind().name(), rule.value(), i.maxQty(), i.soldQty(),
          i.maxQty() == null ? null : Math.max(0, i.maxQty() - i.soldQty()), i.perCustomerLimit(), effLimit);
    }).toList();
    return new SaleView(s.id(), s.name(), s.description(), s.bannerText(), status(s, now).name(), s.startsAt(), s.endsAt(), s.channel(),
        s.discountKind(), s.discountValue(), s.perCustomerLimit(), s.cancelledAt(), s.createdBy(), s.createdAt(), s.updatedAt(), views);
  }

  // ---------- read (customers) ----------
  /** Sales running now for online customers. Units left are read per request; the definitions come from the cache. */
  @Transactional(readOnly = true)
  public ShopSales active() { return shopSales(true); }

  /** Sales that have not started yet (price shown is today's sale price). */
  @Transactional(readOnly = true)
  public ShopSales upcoming() { return shopSales(false); }

  private ShopSales shopSales(boolean running) {
    Instant now = clock.instant();
    FlashPricingService.Defs defs = pricing.defs();
    Map<UUID, List<FlashPricingService.ItemDef>> bySale = new LinkedHashMap<>();
    for (var sw : defs.sales()) {
      Sale s = sw.sale();
      boolean on = !now.isBefore(s.startsAt()) && now.isBefore(s.endsAt());
      boolean coming = now.isBefore(s.startsAt());
      if ((running ? !on : !coming) || "POS".equals(s.channel())) continue;
      bySale.put(s.id(), new ArrayList<>());
    }
    defs.byProduct().values().forEach(list -> list.forEach(d -> {
      if (bySale.containsKey(d.saleId())) bySale.get(d.saleId()).add(d);
    }));
    List<UUID> productIds = bySale.values().stream().flatMap(List::stream).map(FlashPricingService.ItemDef::productId).distinct().toList();
    Map<UUID, Product> products = catalog.visible(productIds).stream().collect(Collectors.toMap(Product::id, p -> p, (a, _) -> a));
    Map<UUID, Integer> sold = repo.soldQtys(bySale.values().stream().flatMap(List::stream).map(FlashPricingService.ItemDef::itemId).toList());
    List<ShopSale> out = new ArrayList<>();
    for (var sw : defs.sales()) {
      List<FlashPricingService.ItemDef> ds = bySale.get(sw.sale().id());
      if (ds == null) continue;
      List<ShopItem> items = new ArrayList<>();
      for (FlashPricingService.ItemDef d : ds) {
        Product p = products.get(d.productId());
        if (p == null) continue;
        var price = FlashMath.salePrice(d.rule(), p.pricePaise());
        if (price.isEmpty()) continue;
        Integer left = d.maxQty() == null ? null : Math.max(0, d.maxQty() - sold.getOrDefault(d.itemId(), 0));
        items.add(new ShopItem(p.id(), p.nameEn(), p.nameGu(), p.imageUrl(), p.packSize() == null || p.packSize().isBlank() ? p.unit() : p.packSize(),
            p.pricePaise(), price.get(), FlashMath.discountPercent(p.pricePaise(), price.get()), left, d.customerLimit(), p.inStock()));
      }
      if (items.isEmpty()) continue;
      Sale s = sw.sale();
      out.add(new ShopSale(s.id(), s.name(), s.description(), s.bannerText(), s.startsAt(), s.endsAt(),
          Math.max(0, Duration.between(now, s.endsAt()).getSeconds()), Math.max(0, Duration.between(now, s.startsAt()).getSeconds()), items));
    }
    return new ShopSales(now, out);
  }

  // ---------- helpers ----------
  private Sale sale(UUID id) { return repo.findSale(id).orElseThrow(() -> ApiException.notFound("Flash sale")); }

  private Item item(UUID saleId, UUID itemId) {
    return repo.findItem(itemId).filter(i -> i.flashSaleId().equals(saleId)).orElseThrow(() -> ApiException.notFound("Flash sale item"));
  }

  private static FlashStatus status(Sale s, Instant now) { return FlashStatus.of(s.startsAt(), s.endsAt(), s.cancelledAt(), now); }

  private static void requireOpen(FlashStatus st) {
    if (st == FlashStatus.ENDED || st == FlashStatus.CANCELLED) throw notAllowed("This sale is " + st + " and can no longer be changed");
  }

  private static ApiException notAllowed(String message) { return ApiException.conflict("FLASH_NOT_ALLOWED", message, Map.of()); }

  private void touch(Sale s, Instant now) {
    repo.updateSale(new Sale(s.id(), s.name(), s.description(), s.bannerText(), s.startsAt(), s.endsAt(), s.channel(), s.discountKind(),
        s.discountValue(), s.perCustomerLimit(), s.cancelledAt(), s.createdBy(), s.createdAt(), now));
  }

  private static Sale withTimes(Sale s, Instant start, Instant end, Instant now) {
    return new Sale(s.id(), s.name(), s.description(), s.bannerText(), start, end, s.channel(), s.discountKind(), s.discountValue(),
        s.perCustomerLimit(), s.cancelledAt(), s.createdBy(), s.createdAt(), now);
  }

  private Map<UUID, ProductData> productsById(Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    return catalog.posProducts(ids).stream().collect(Collectors.toMap(ProductData::id, p -> p, (a, _) -> a));
  }

  private void requireNoClash(List<UUID> productIds, Instant start, Instant end, UUID except) {
    List<FlashRepository.Clash> clashes = repo.clashes(productIds, start, end, except);
    if (clashes.isEmpty()) return;
    FlashRepository.Clash c = clashes.get(0);
    String product = catalog.names(List.of(c.productId())).getOrDefault(c.productId(), c.productId().toString());
    throw ApiException.conflict("FLASH_OVERLAP", product + " is already in the flash sale \"" + c.saleName() + "\" at that time",
        Map.of("saleId", c.saleId(), "saleName", c.saleName(), "productId", c.productId()));
  }

  private void changed() { live.area(CatalogChanged.FLASH); }

  private static LoggingEventBuilder logEvent(String event, Sale s, UUID actor) {
    return log.atInfo().setMessage("Flash sale " + event.substring("flash-".length())).addKeyValue("event", event).addKeyValue("saleId", s.id())
        .addKeyValue("name", s.name()).addKeyValue("startsAt", s.startsAt()).addKeyValue("endsAt", s.endsAt()).addKeyValue("actor", actor);
  }

  private static String name(String raw) {
    String n = raw == null ? "" : raw.strip();
    if (n.isEmpty() || n.length() > MAX_NAME) throw ApiException.validation("name", "name must be 1 to " + MAX_NAME + " characters");
    return n;
  }

  private static String text(String field, String raw, int max) {
    String t = raw == null ? "" : raw.strip();
    if (t.length() > max) throw ApiException.validation(field, "must be at most " + max + " characters");
    return t;
  }

  private static String channel(String raw) {
    if (raw == null || raw.isBlank()) return "BOTH";
    String c = raw.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("ONLINE", "POS", "BOTH").contains(c)) throw ApiException.validation("channel", "must be ONLINE, POS or BOTH");
    return c;
  }

  private static FlashKind kind(String field, String raw) {
    try {
      return FlashKind.parse(raw);
    } catch (IllegalArgumentException e) {
      throw ApiException.validation(field, "discount kind " + e.getMessage());
    }
  }

  private static long ruleValue(String field, FlashKind kind, Long value) {
    if (kind == FlashKind.NONE) return 0;
    String problem = FlashMath.problemWith(kind, value == null ? 0 : value);
    if (problem != null) throw ApiException.validation(field, problem);
    return value;
  }

  private static int limit(String field, Integer v, int fallback) {
    if (v == null) return fallback;
    if (v < 0 || v > MAX_CUSTOMER_LIMIT) throw ApiException.validation(field, "must be 0 to " + MAX_CUSTOMER_LIMIT);
    return v;
  }

  private static void window(Instant start, Instant end, Instant now, boolean startMayBePast) {
    String problem = FlashWindow.problem(start, end, now, startMayBePast);
    if (problem != null) throw ApiException.validation(problem.startsWith("startsAt") ? "startsAt" : "endsAt", problem);
  }

  private static String rupees(long paise) { return "%d.%02d".formatted(paise / 100, paise % 100); }
}
