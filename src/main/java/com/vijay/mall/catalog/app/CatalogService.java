package com.vijay.mall.catalog.app;

import com.vijay.mall.catalog.domain.AdminCategory;
import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.catalog.domain.Category;
import com.vijay.mall.catalog.domain.HotBanner;
import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.catalog.infra.BannerRepository;
import com.vijay.mall.catalog.infra.CategoryRepository;
import com.vijay.mall.catalog.infra.ProductRepository;
import com.vijay.mall.common.FlashPricing;
import com.vijay.mall.common.Limits;
import com.vijay.mall.common.OrderLimits;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.ReferenceNumbers;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shop catalog: products grouped into variants, categories, hot-sale banners and the admin operations on them.
 * <p>Customers read products through the {@code list}, {@code get}, {@code groups} and {@code hotSale} methods (inactive and
 * counter-only items are hidden, flash-sale offers are attached). The admin methods create and edit products, adjust stock
 * through the inventory module (every change leaves a stock movement) and write an audit entry. Prices are integer paise and the
 * MRP may never be below the selling price. Cart pricing for orders is done by {@link #price}.
 */
@Service
public class CatalogService {
  /** One cart line to be priced: a product (variant) id and the wanted quantity. */
  public record ItemRequest(UUID productId, int qty) {}

  /**
   * Admin input to create or update one product (one variant).
   * <p>{@code qtyOnHand} and {@code lowThreshold} of -1 mean "keep the current value" on update; on create they become 0 stock and
   * the default low-stock alert level of 5. {@code productGroupId} null keeps the existing group (a new product is its own group).
   */
  public record ProductInput(UUID categoryId, String nameEn, String nameGu, String description, long mrpPaise,
                             long pricePaise, String unit, String packSize, boolean active, boolean hotSale,
                             Instant hotStart, Instant hotEnd, boolean earnsCoins, boolean coinRedeemable,
                             int qtyOnHand, int lowThreshold, UUID productGroupId) {
    /** Without a group: a new product is its own group, an existing one keeps its group. */
    public ProductInput(UUID categoryId, String nameEn, String nameGu, String description, long mrpPaise,
                        long pricePaise, String unit, String packSize, boolean active, boolean hotSale,
                        Instant hotStart, Instant hotEnd, boolean earnsCoins, boolean coinRedeemable,
                        int qtyOnHand, int lowThreshold) {
      this(categoryId, nameEn, nameGu, description, mrpPaise, pricePaise, unit, packSize, active, hotSale, hotStart,
          hotEnd, earnsCoins, coinRedeemable, qtyOnHand, lowThreshold, null);
    }
  }

  /**
   * One row of the admin stock report. packLabel is the pack size (or unit); cashierId is whoever made the change.
   * refId is the order, counter bill or return behind the change and ref its number as people read it (the same
   * VSM-1001 the customer sees); both are null for a manual adjustment.
   */
  public record StockMovementView(UUID id, UUID productId, String name, String packLabel, int delta, String reason,
                                  Instant at, UUID cashierId, UUID refId, String ref) {}

  /**
   * The hot-sale screen: the banners that are live now (all of them) and one page of the in-stock products currently on hot
   * sale, soonest ending window first. page, size, total, totalExact and hasMore describe the products part only and mean
   * the same as in {@link Page}.
   */
  public record HotSale(List<HotBanner> banners, List<Product> products, int page, int size, long total, boolean totalExact,
                        boolean hasMore) {
    /** A single page holding all the given products. */
    public HotSale(List<HotBanner> banners, List<Product> products) {
      this(banners, products, 0, products.size(), products.size(), true, false);
    }
  }

  /** What a product delete did: removed for good, or only switched off because it has history. */
  public record DeleteResult(boolean deleted, boolean deactivated, String reason) {}

  /** Which variants of a product group a picture upload applies to. */
  public enum ImageScope {
    /** Only the addressed pack (the default). */
    ONE,
    /** The addressed pack and every other variant of the group that has no picture yet. */
    MISSING,
    /** Every variant of the group, replacing pictures already there. */
    ALL;

    /** Parses the {@code variants} request value (case-insensitive); null or blank means {@link #ONE}. */
    public static ImageScope parse(String text) {
      if (text == null || text.isBlank()) return ONE;
      try {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw ApiException.validation("variants", "must be one, missing or all");
      }
    }
  }

  /**
   * One sellable variant of a product (for example "Mango, 500 g"). id is what the cart, the order and the review use.
   * label is the text to show next to the price: the pack size, or the unit when there is no pack size.
   */
  public record Variant(UUID id, String variantName, String packSize, String unit, String label, long mrpPaise,
                        long pricePaise, String imageUrl, boolean inStock, int qtyAvailable,
                        FlashPricing.Offer flash) {
    /** A variant not on a flash sale. */
    public Variant(UUID id, String variantName, String packSize, String unit, String label, long mrpPaise,
                   long pricePaise, String imageUrl, boolean inStock, int qtyAvailable) {
      this(id, variantName, packSize, unit, label, mrpPaise, pricePaise, imageUrl, inStock, qtyAvailable, null);
    }
  }

  /**
   * One product with all its variants, the way a shop shows it: a single card, a variant picker, one rating.
   * Name, description, category and flags are shared by every variant. defaultVariantId is the first variant in
   * display order that is in stock (or the first when none is). fromPricePaise is the lowest variant price.
   * rating and reviewCount cover the reviews of all variants together.
   */
  public record ProductGroup(UUID groupId, UUID defaultVariantId, UUID categoryId, String nameEn, String nameGu,
                             String description, String imageUrl, boolean hotSale, boolean earnsCoins,
                             boolean coinRedeemable, double rating, int reviewCount, long fromPricePaise, boolean inStock,
                             List<Variant> variants, FlashPricing.Offer flash) {
    /** A product not on a flash sale. */
    public ProductGroup(UUID groupId, UUID defaultVariantId, UUID categoryId, String nameEn, String nameGu,
                        String description, String imageUrl, boolean hotSale, boolean earnsCoins,
                        boolean coinRedeemable, double rating, int reviewCount, long fromPricePaise, boolean inStock,
                        List<Variant> variants) {
      this(groupId, defaultVariantId, categoryId, nameEn, nameGu, description, imageUrl, hotSale, earnsCoins, coinRedeemable, rating,
          reviewCount, fromPricePaise, inStock, variants, null);
    }
  }

  /** A new variant of an existing product. It copies the shared details from that product. */
  public record VariantInput(String variantName, String packSize, String unit, long mrpPaise, long pricePaise,
                             Boolean active, int qtyOnHand, int lowThreshold, Integer variantOrder) {}

  /** Details shared by every variant of a product; editing them once updates all variants. */
  public record GroupInput(UUID categoryId, String nameEn, String nameGu, String description, Boolean hotSale,
                           Instant hotStart, Instant hotEnd, Boolean earnsCoins, Boolean coinRedeemable, Boolean active) {}

  private final ProductRepository products;
  private final CategoryRepository categories;
  private final BannerRepository banners;
  private final InventoryService inventory;
  private final AuditService audit;
  private final Clock clock;
  private final ReferenceNumbers references;
  private final UnitService units;

  private LiveEvents live;

  /** Optional: tells open app screens about changes (not provided in plain unit tests). */
  @Autowired(required = false)
  void setLive(LiveEvents live) { this.live = live; }

  private void changed() { if (live != null) live.catalog(); }

  private FlashPricing flash;

  /** Optional (plain unit tests run without it): shows the flash sale price on products. */
  @Autowired(required = false)
  void setFlash(FlashPricing flash) { this.flash = flash; }

  private List<Product> flashed(List<Product> list) {
    if (flash == null || list.isEmpty()) return list;
    Map<UUID, Long> prices = new LinkedHashMap<>();
    list.forEach(p -> prices.put(p.id(), p.pricePaise()));
    Map<UUID, FlashPricing.Offer> offers = flash.offers(prices);
    return offers.isEmpty() ? list : list.stream().map(p -> offers.containsKey(p.id()) ? p.withFlash(offers.get(p.id())) : p).toList();
  }

  private ProductGroup flashed(ProductGroup g) {
    if (flash == null) return g;
    Map<UUID, Long> prices = new LinkedHashMap<>();
    g.variants().forEach(v -> prices.put(v.id(), v.pricePaise()));
    Map<UUID, FlashPricing.Offer> offers = flash.offers(prices);
    if (offers.isEmpty()) return g;
    List<Variant> vs = g.variants().stream().map(v -> new Variant(v.id(), v.variantName(), v.packSize(), v.unit(), v.label(), v.mrpPaise(),
        v.pricePaise(), v.imageUrl(), v.inStock(), v.qtyAvailable(), offers.get(v.id()))).toList();
    var first = vs.stream().filter(v -> v.flash() != null).findFirst().map(Variant::flash).orElse(null);
    return new ProductGroup(g.groupId(), g.defaultVariantId(), g.categoryId(), g.nameEn(), g.nameGu(), g.description(), g.imageUrl(), g.hotSale(),
        g.earnsCoins(), g.coinRedeemable(), g.rating(), g.reviewCount(), g.fromPricePaise(), g.inStock(), vs, first);
  }

  /** Without a unit list, any unit text is accepted as before (plain unit tests). */
  public CatalogService(ProductRepository products, CategoryRepository categories, BannerRepository banners,
                        InventoryService inventory, AuditService audit, Clock clock,
                        ReferenceNumbers references) {
    this(products, categories, banners, inventory, audit, clock, references, null);
  }

  /**
   * Main constructor used by Spring.
   *
   * @param units the admin-managed unit list used to validate and normalise product units
   */
  @Autowired
  public CatalogService(ProductRepository products, CategoryRepository categories, BannerRepository banners,
                        InventoryService inventory, AuditService audit, Clock clock,
                        ReferenceNumbers references, UnitService units) {
    this.units = units;
    this.products = products;
    this.categories = categories;
    this.banners = banners;
    this.inventory = inventory;
    this.audit = audit;
    this.clock = clock;
    this.references = references;
  }

  // ---------- customer side ----------
  /** English names of the given products keyed by id; unknown ids are simply absent. */
  public Map<UUID, String> names(Collection<UUID> ids) { return products.names(ids); }

  /** When any product last changed (the offline till compares this with the copy it holds). */
  public Instant lastChange() { return products.lastChange(); }

  /** Names of products still on sale; stock alerts ignore retired products. */
  public Map<UUID, String> activeNames(Collection<UUID> ids) { return products.activeNames(ids); }

  private AppCaches caches;

  /** Optional (plain unit tests run without it): the category list rarely changes and is read on every screen. */
  @Autowired(required = false)
  void setCaches(AppCaches caches) { this.caches = caches; }

  /**
   * Remembers the result of a product search (the {@code product-list} cache). What is kept is only which products match, in what
   * order and how many: stock numbers and flash offers are read fresh for every answer. The remembered result is emptied the
   * moment something that changes it commits: any catalog edit, and a product selling out or coming back in stock (see
   * {@code InventoryService}); a 5-minute life is only a backstop, not what keeps it correct.
   */
  private <T> T searched(String key, Supplier<T> search) {
    return caches == null ? search.get() : caches.get(AppCaches.Name.PRODUCT_LIST, key, search);
  }


  /**
   * The active categories in display order. Served from a cache when one is available because every screen reads it.
   */
  public List<Category> categories() {
    return caches == null ? categories.listActive()
        : caches.get(AppCaches.Name.CATEGORIES, "active", categories::listActive);
  }

  /**
   * The English name of a category, active or not, for other modules that need only the label (for example the GST master matching a
   * product by name and category).
   * @param id the category id; may be {@code null}
   * @return the name, or empty when the id is {@code null} or unknown
   */
  public Optional<String> categoryName(UUID id) {
    return id == null ? Optional.empty() : categories.find(id).map(Category::nameEn);
  }

  /** The shop list. Products with no stock are left out; use the overload to include them. */
  public Page<Product> list(String q, UUID categoryId, boolean hot, int page, int size) {
    return list(q, categoryId, hot, false, page, size);
  }

  /**
   * The customer product list, one row per variant, sorted by English name.
   * <p>Counter-only and inactive products are never returned; flash-sale offers are attached to the rows.
   * @param q optional text matched against the English and Gujarati names
   * @param categoryId optional category filter
   * @param hot only products whose hot-sale flag and window are open now
   * @param includeOutOfStock also return products with no stock
   * @param page zero-based page number
   * @param size requested page size (clamped)
   */
  public Page<Product> list(String q, UUID categoryId, boolean hot, boolean includeOutOfStock, int page, int size) {
    Instant now = clock.instant();
    int pg = Math.max(0, page);
    int sz = Page.clampSize(size);
    String key = "list|" + q + "|" + categoryId + "|" + includeOutOfStock + "|" + pg + "|" + sz;
    Supplier<Page<ProductData>> search = () -> includeOutOfStock ? products.search(q, categoryId, hot, true, now, pg, sz)
        : products.searchInStock(q, categoryId, hot, now, pg, sz);
    Page<AdminProduct> p = withStock(hot ? search.get() : searched(key, search)); // "on hot sale now" depends on the clock: never remembered
    return p.withItems(flashed(p.items().stream().map(a -> a.toCustomer(now)).toList()));
  }

  /**
   * One product as customers see it, with any flash-sale offer.
   * @throws ApiException {@code NOT_FOUND} when the product is unknown, inactive or counter-only
   */
  public Product get(UUID id) {
    AdminProduct p = one(id);
    if (!p.active() || products.isPosOnly(id)) throw ApiException.notFound("Product");
    return flashed(List.of(p.toCustomer(clock.instant()))).get(0);
  }

  /**
   * Builds the hot-sale screen: one page (default 20) of in-stock hot products and the banners whose active flag and time window are open now.
   * The full banner list is cached; which banners are live is decided against the clock on every call.
   */
  public HotSale hotSale() { return hotSale(0, 20); }

  /**
   * The hot-sale screen with paging for the products part (banners are always returned whole).
   * <p>Products are the in-stock ones on hot sale now, the soonest ending window first (no end date last), then by name.
   * @param page zero-based page number (negative counts as 0)
   * @param size page size, 1 to 50 (default 20 on the endpoint)
   */
  public HotSale hotSale(int page, int size) {
    Instant now = clock.instant();
    int pg = Math.max(0, page);
    int sz = Page.clampSize(size);
    Page<AdminProduct> found = withStock(products.searchHotInStock(now, pg, sz));
    List<Product> list = flashed(found.items().stream().map(a -> a.toCustomer(now)).toList());
    // all banners are cached (they change rarely); which ones are on right now depends on the clock, so that is decided here
    List<HotBanner> all = caches == null ? banners.all() : caches.get(AppCaches.Name.BANNERS, "all", banners::all);
    return new HotSale(all.stream().filter(b -> b.active() && (b.startAt() == null || !b.startAt().isAfter(now))
        && (b.endAt() == null || !b.endAt().isBefore(now))).toList(), list, found.page(), found.size(), found.total(),
        found.totalExact(), found.hasMore());
  }

  /** Products with their variants, one entry per product. A product matches when any of its variants does. */
  public Page<ProductGroup> groups(String q, UUID categoryId, boolean hot, int page, int size) {
    return groups(q, categoryId, hot, false, page, size);
  }

  /** With includeOutOfStock false (the default) products with no stock are left out, and so are a product's sold-out variants. */
  @Transactional(readOnly = true)
  public Page<ProductGroup> groups(String q, UUID categoryId, boolean hot, boolean includeOutOfStock, int page, int size) {
    Instant now = clock.instant();
    int pg = Math.max(0, page);
    int sz = Page.clampSize(size);
    String key = "groups|" + q + "|" + categoryId + "|" + includeOutOfStock + "|" + pg + "|" + sz;
    Supplier<Page<UUID>> search = () -> products.searchGroups(q, categoryId, hot, !includeOutOfStock, now, pg, sz);
    Page<UUID> ids = hot ? search.get() : searched(key, search);
    List<ProductData> rows = products.visibleMembers(ids.items());
    var stocks = inventory.stocks(rows.stream().map(ProductData::id).toList());
    if (!includeOutOfStock) {
      var inStock = stocks.entrySet().stream().filter(e -> e.getValue().qty() > 0).map(Map.Entry::getKey).collect(Collectors.toSet());
      rows = rows.stream().filter(r -> inStock.contains(r.id())).toList();
    }
    var info = products.variantInfo(rows.stream().map(ProductData::id).toList());
    Map<UUID, List<ProductData>> byGroup = rows.stream()
        .collect(Collectors.groupingBy(ProductData::productGroupId, LinkedHashMap::new, Collectors.toList()));
    List<ProductGroup> items = ids.items().stream().filter(byGroup::containsKey)
        .map(g -> flashed(toGroup(byGroup.get(g), stocks, info, now))).toList();
    return ids.withItems(items);
  }

  /** One product with its variants. The id may be the group id or the id of any of its variants. */
  @Transactional(readOnly = true)
  public ProductGroup group(UUID idOrGroupId) {
    Instant now = clock.instant();
    List<ProductData> rows = products.allMembersOf(idOrGroupId).stream()
        .filter(p -> p.active() && !products.isPosOnly(p.id())).toList();
    if (rows.isEmpty()) throw ApiException.notFound("Product");
    var stocks = inventory.stocks(rows.stream().map(ProductData::id).toList());
    var info = products.variantInfo(rows.stream().map(ProductData::id).toList());
    return flashed(toGroup(rows, stocks, info, now));
  }

  /** Ids of the visible variants of the product that holds this id; used to read the reviews of all variants together. */
  @Transactional(readOnly = true)
  public List<UUID> variantIds(UUID idOrGroupId) {
    return group(idOrGroupId).variants().stream().map(Variant::id).toList();
  }

  private static ProductGroup toGroup(List<ProductData> members,
                                      Map<UUID, InventoryRepository.Stock> stocks,
                                      Map<UUID, ProductRepository.VariantInfo> info, Instant now) {
    var none = new ProductRepository.VariantInfo("", 0);
    List<ProductData> sorted = members.stream().sorted(Comparator
        .comparingInt((ProductData p) -> info.getOrDefault(p.id(), none).order())
        .thenComparingLong(ProductData::pricePaise)).toList();
    List<Variant> variants = sorted.stream().map(p -> {
      var stock = stocks.get(p.id());
      int qty = stock == null ? 0 : stock.qty();
      return new Variant(p.id(), info.getOrDefault(p.id(), none).name(), p.packSize() == null ? "" : p.packSize(), p.unit(),
          p.unitLabel(), p.mrpPaise(), p.pricePaise(), p.imageUrl(), qty > 0, Math.min(qty, 99));
    }).toList();
    Variant def = variants.stream().filter(Variant::inStock).findFirst().orElse(variants.get(0));
    String image = def.imageUrl() != null ? def.imageUrl()
        : variants.stream().map(Variant::imageUrl).filter(u -> u != null).findFirst().orElse(null);
    // a pack without its own picture shows the product's picture
    if (image != null) {
      variants = variants.stream().map(v -> v.imageUrl() != null ? v : new Variant(v.id(), v.variantName(), v.packSize(), v.unit(),
          v.label(), v.mrpPaise(), v.pricePaise(), image, v.inStock(), v.qtyAvailable())).toList();
    }
    ProductData shared = sorted.get(0);
    int reviews = sorted.stream().mapToInt(ProductData::reviewCount).sum();
    double weighted = sorted.stream().mapToDouble(p -> p.rating() * p.reviewCount()).sum();
    double rating = reviews == 0 ? 0 : Math.round(weighted / reviews * 100.0) / 100.0;
    boolean hot = sorted.stream().anyMatch(p -> p.hotSale() && (p.hotStart() == null || !now.isBefore(p.hotStart()))
        && (p.hotEnd() == null || !now.isAfter(p.hotEnd())));
    return new ProductGroup(shared.productGroupId(), def.id(), shared.categoryId(), shared.nameEn(), shared.nameGu(),
        shared.description(), image, hot, shared.earnsCoins(), shared.coinRedeemable(), rating, reviews,
        variants.stream().mapToLong(Variant::pricePaise).min().orElse(0), variants.stream().anyMatch(Variant::inStock),
        variants);
  }

  /** Prices a cart from the current catalog. Unknown or inactive products are rejected. */
  public List<PricedLine> price(List<ItemRequest> items) {
    if (items == null || items.isEmpty()) throw ApiException.validation("items", "cart is empty");
    items.forEach(it -> {
      if (it.qty() <= 0 || it.qty() > OrderLimits.MAX_LINE_QTY) {
        throw ApiException.validation("qty", "quantity must be 1 to " + OrderLimits.MAX_LINE_QTY);
      }
    });
    Map<UUID, Integer> merged = items.stream().collect(
        Collectors.toMap(ItemRequest::productId, ItemRequest::qty, Integer::sum, LinkedHashMap::new));
    // the same product listed twice counts once, so the limit applies to the total
    if (merged.values().stream().anyMatch(q -> q > OrderLimits.MAX_LINE_QTY)) {
      throw ApiException.validation("qty", "quantity must be 1 to " + OrderLimits.MAX_LINE_QTY);
    }
    Map<UUID, ProductData> byId = products.findAll(List.copyOf(merged.keySet())).stream()
        .collect(Collectors.toMap(ProductData::id, Function.identity()));
    Set<UUID> counterOnly = products.posOnlyAmong(merged.keySet());
    return merged.entrySet().stream().map(e -> {
      ProductData p = byId.get(e.getKey());
      if (p == null || !p.active() || counterOnly.contains(p.id())) throw ApiException.validation("productId", "product is not available: " + e.getKey());
      return new PricedLine(p.id(), p.categoryId(), p.nameEn(), e.getValue(), p.pricePaise(),
          p.pricePaise() * e.getValue(), p.coinRedeemable(), p.earnsCoins(), p.nameGu(), p.unitLabel(), null, 0, p.productGroupId());
    }).toList();
  }

  /** Customer view of these products in the same order, leaving out any that are inactive or counter-only. */
  public List<Product> visible(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    Instant now = clock.instant();
    var stocks = inventory.stocks(ids);
    Set<UUID> counterOnly = products.posOnlyAmong(ids);
    Map<UUID, ProductData> byId = products.findAll(ids).stream()
        .collect(Collectors.toMap(ProductData::id, Function.identity()));
    return flashed(ids.stream().map(byId::get)
        .filter(p -> p != null && p.active() && !counterOnly.contains(p.id()))
        .map(p -> p.withStock(stocks.get(p.id()).qty(), stocks.get(p.id()).low()).toCustomer(now)).toList());
  }

  /**
   * Review average and count, written by the review service after every change. The rating is part of what the remembered product lists
   * hold, so this announces a catalog change: the lists are emptied when the review commits.
   */
  @Transactional
  public void updateRating(UUID productId, double rating, int reviewCount) {
    products.setRating(productId, rating, reviewCount);
    changed();
  }

  // ---------- counter (POS) side ----------
  /** Active products for the counter, including counter-only items. */
  public Page<ProductData> posSearch(String q, int page, int size) {
    return products.search(q, null, false, true, true, clock.instant(), Math.max(0, page), Page.clampSize(size));
  }

  /**
   * Counter search by name that also returns the products in {@code exactIds} (exact barcode or item code matches) first, and
   * can keep only products changed at or after {@code since} (the product, its counter attributes or its barcodes).
   */
  public Page<ProductData> posSearch(String q, Collection<UUID> exactIds, Instant since, int page, int size) {
    return products.searchForCounter(q, exactIds, since, Math.max(0, page), Page.clampSize(size));
  }

  /** The variant name of each of these products (empty text when it has none), for the counter. */
  public Map<UUID, String> posVariantNames(Collection<UUID> ids) {
    Map<UUID, String> out = new HashMap<>();
    products.variantInfo(ids).forEach((id, v) -> out.put(id, v.name()));
    return out;
  }

  /** Stored product data for the counter (POS) for these ids, including inactive and counter-only items. */
  public List<ProductData> posProducts(Collection<UUID> ids) { return products.findAll(List.copyOf(ids)); }

  /**
   * Marks a product as sold at the counter only (hidden from the online shop) or puts it back online.
   * @throws ApiException {@code NOT_FOUND} when the product does not exist
   */
  @Transactional
  public void setPosOnly(UUID id, boolean posOnly) {
    adminGet(id);
    products.setPosOnly(id, posOnly);
    changed();
  }

  // ---------- admin side ----------
  /** The ways the admin product list can be sorted. */
  private static final List<String> ADMIN_SORTS = List.of("name", "lowStock", "updated");

  /** Admin product list with stock numbers; includes inactive and counter-only products, sorted by English name. */
  public Page<AdminProduct> adminList(String q, UUID categoryId, int page, int size) {
    return adminList(q, categoryId, null, null, false, null, page, size);
  }

  /**
   * Admin product list with stock numbers, narrowed and sorted as asked.
   *
   * @param hotSale true for products flagged hot sale, false for the others, {@code null} for both
   * @param active true for active products, false for retired ones, {@code null} for both
   * @param lowStock true to keep only products at or below their stock alert level
   * @param sort {@code name} (default), {@code lowStock} (emptiest first) or {@code updated} (newest change first)
   * @throws ApiException 400 {@code fields.sort} for any other sort
   */
  public Page<AdminProduct> adminList(String q, UUID categoryId, Boolean hotSale, Boolean active, boolean lowStock, String sort,
                                      int page, int size) {
    String order = sort == null || sort.isBlank() ? "name" : sort.trim();
    if (!ADMIN_SORTS.contains(order)) throw ApiException.validation("sort", "must be one of " + String.join(", ", ADMIN_SORTS));
    int pg = Math.max(0, page);
    int sz = Page.clampSize(size);
    Supplier<Page<ProductData>> search = () -> products.adminSearch(q, categoryId, hotSale, active, lowStock, order, pg, sz);
    // stock decides the low-stock filter and order, and stock changes do not touch the catalog cache: never remembered
    boolean byStock = lowStock || order.equals("lowStock");
    String key = "admin|" + q + "|" + categoryId + "|" + hotSale + "|" + active + "|" + order + "|" + pg + "|" + sz;
    return withStock(byStock ? search.get() : searched(key, search));
  }

  /** One product with stock numbers for the admin; {@code NOT_FOUND} when unknown. */
  public AdminProduct adminGet(UUID id) { return one(id); }

  /** Stock history with product names, newest first. productId null means all products. */
  public Page<StockMovementView> stockMovements(UUID productId, int page, int size) {
    Page<InventoryRepository.Movement> rows = inventory.movements(productId, page, size);
    Map<UUID, ProductData> byId = products.findAll(rows.items().stream().map(m -> m.productId()).distinct().toList())
        .stream().collect(Collectors.toMap(ProductData::id, Function.identity()));
    Map<UUID, String> numbers = references.of(rows.items().stream().map(m -> m.refId()).filter(Objects::nonNull)
        .distinct().toList());
    List<StockMovementView> items = rows.items().stream().map(m -> {
      ProductData p = byId.get(m.productId());
      return new StockMovementView(m.id(), m.productId(), p == null ? "?" : p.nameEn(), p == null ? "" : p.unitLabel(),
          m.delta(), m.reason(), m.at(), m.actorId(), m.refId(), m.refId() == null ? null : numbers.get(m.refId()));
    }).toList();
    return rows.withItems(items);
  }

  private AdminProduct one(UUID id) {
    ProductData d = products.find(id).orElseThrow(() -> ApiException.notFound("Product"));
    var stock = inventory.stocks(List.of(id)).get(id);
    return d.withStock(stock.qty(), stock.low());
  }

  /** Adds stock numbers to a page of products with one query instead of one per product. */
  private Page<AdminProduct> withStock(Page<ProductData> page) {
    var stocks = inventory.stocks(page.items().stream().map(ProductData::id).toList());
    List<AdminProduct> items = page.items().stream()
        .map(d -> d.withStock(stocks.get(d.id()).qty(), stocks.get(d.id()).low())).toList();
    return page.withItems(items);
  }

  /** The unit to store: checked against the admin's unit list when there is one, else the text as given. */
  private String unit(String given, String current) {
    if (units != null) return units.canonicalOrKeep(given, current);
    return nz(given, current == null ? "pc" : current);
  }

  /**
   * Creates a product and its inventory row, and writes an audit entry.
   * @param in the product details; prices must not be negative and the MRP must not be below the price
   * @param actor the admin making the change
   * @return the new product with stock numbers
   * @throws ApiException validation error for a bad price, an invalid hot-sale window or an unknown unit
   */
  @Transactional
  public AdminProduct create(ProductInput in, UUID actor) {
    validate(in);
    UUID id = products.insert(in.categoryId(), in.nameEn(), in.nameGu(), nz(in.description()), in.mrpPaise(),
        in.pricePaise(), unit(in.unit(), null), nz(in.packSize()), in.active(), in.hotSale(), in.hotStart(),
        in.hotEnd(), in.earnsCoins(), in.coinRedeemable(), clock.instant());
    if (in.productGroupId() != null) products.setGroup(id, in.productGroupId());
    inventory.create(id, Math.max(0, in.qtyOnHand()), in.lowThreshold() < 0 ? 5 : in.lowThreshold());
    audit.log(actor, "CREATE", "product", id, in.nameEn() + " @ " + in.pricePaise());
    changed();
    return adminGet(id);
  }

  /**
   * Updates a product's details; a different {@code qtyOnHand} is applied as an {@code ADJUST} stock movement and a
   * non-negative {@code lowThreshold} replaces the alert level.
   * @throws ApiException {@code NOT_FOUND} when the product is unknown, or a validation error as for create
   */
  @Transactional
  public AdminProduct update(UUID id, ProductInput in, UUID actor) {
    validate(in);
    AdminProduct before = adminGet(id);
    products.update(id, in.categoryId(), in.nameEn(), in.nameGu(), nz(in.description()), in.mrpPaise(),
        in.pricePaise(), unit(in.unit(), before.unit()), nz(in.packSize()), in.active(), in.hotSale(), in.hotStart(),
        in.hotEnd(), in.earnsCoins(), in.coinRedeemable(), clock.instant());
    if (in.productGroupId() != null) products.setGroup(id, in.productGroupId());
    int delta = in.qtyOnHand() - before.qtyOnHand();
    if (delta != 0 && in.qtyOnHand() >= 0) inventory.adjust(id, delta, "ADJUST", actor);
    if (in.lowThreshold() >= 0) inventory.setLowThreshold(id, in.lowThreshold());
    audit.log(actor, "UPDATE", "product", id, "price " + before.pricePaise() + " -> " + in.pricePaise());
    changed();
    return adminGet(id);
  }

  /**
   * Adds or removes stock by a relative amount and records the stock movement and an audit entry.
   * @param delta units to add (positive) or remove (negative)
   * @param reason {@code ADJUST} for a correction; any other value is recorded as {@code RESTOCK}
   * @param actor the admin making the change
   */
  @Transactional
  public AdminProduct restock(UUID id, int delta, String reason, UUID actor) {
    adminGet(id);
    inventory.adjust(id, delta, "ADJUST".equals(reason) ? "ADJUST" : "RESTOCK", actor);
    audit.log(actor, "STOCK", "product", id, "delta " + delta);
    return adminGet(id);
  }

  /** Stores the already-uploaded picture path on the product and tells open screens the catalog changed. */
  @Transactional
  public AdminProduct setImage(UUID id, String url) {
    adminGet(id);
    products.setImage(id, url, clock.instant());
    changed();
    return adminGet(id);
  }

  /**
   * Stores the already-uploaded picture on the addressed pack and, depending on the scope, on its siblings.
   * @param scope ONE keeps today's behaviour; MISSING also fills the variants of the group with no picture; ALL overwrites all
   */
  @Transactional
  public AdminProduct setImage(UUID id, String url, ImageScope scope) {
    adminGet(id);
    if (scope == ImageScope.ONE) return setImage(id, url);
    Instant now = clock.instant();
    products.allMembersOf(id).stream()
        .filter(m -> m.id().equals(id) || scope == ImageScope.ALL || m.imageUrl() == null || m.imageUrl().isBlank())
        .forEach(m -> products.setImage(m.id(), url, now));
    changed();
    return adminGet(id);
  }

  /**
   * Deletes a product for good when nothing refers to it, otherwise only switches it off.
   * <p>Never sold, received, reviewed or put on a flash sale means the product, its stock row and movements, barcodes, wish-list
   * and cart rows go together. Anything else keeps the row (history must stay readable) and sets active to false; deleting a
   * product that is already inactive answers deactivated again.
   * @throws ApiException {@code NOT_FOUND} for an unknown id
   */
  @Transactional
  public DeleteResult deleteProduct(UUID id, UUID actor) {
    AdminProduct p = adminGet(id);
    String used = products.usedBy(id);
    if (used == null) {
      products.purge(id);
      audit.log(actor, "PRODUCT_DELETE", "product", id, p.nameEn());
      changed();
      return new DeleteResult(true, false, "Never sold, received or reviewed: removed completely");
    }
    String why = "Has " + used + ": kept for history and switched off instead";
    if (p.active()) {
      products.setActive(id, false, clock.instant());
      audit.log(actor, "PRODUCT_DEACTIVATE", "product", id, p.nameEn());
      changed();
    }
    return new DeleteResult(false, true, why);
  }

  /** Changes only the fields present in the patch (stock never changes here); same validation as a full update. */
  @Transactional
  public AdminProduct patch(UUID id, ProductPatch patch, UUID actor) {
    AdminProduct before = adminGet(id);
    AdminProduct updated = update(id, patch.applyTo(before), actor);
    return patch.touchesVariant() ? setVariant(id, patch.variantName(), patch.variantOrder(), actor) : updated;
  }

  /**
   * What a bulk hot-sale change did.
   * @param changed how many products were set
   * @param ids the products that were set, in the order asked, without repeats
   */
  public record HotSaleResult(int changed, List<UUID> ids) {}

  /**
   * Turns the hot-sale flag of many products on or off in one transaction, with one audit entry; open screens are told once that
   * the catalog changed. The window replaces the old one on every product ({@code null} means no limit).
   *
   * @param ids the products, at most 200; repeated ids count once
   * @param hotStart start of the hot-sale window, or {@code null}
   * @param hotEnd end of the window, or {@code null}; not before the start
   * @throws ApiException {@code NOT_FOUND} (nothing changes) when any id is unknown; 400 for an empty or too long list or a window that ends before it starts
   */
  @Transactional
  public HotSaleResult setHotSale(List<UUID> ids, boolean hotSale, Instant hotStart, Instant hotEnd, UUID actor) {
    List<UUID> distinct = ids == null ? List.of() : ids.stream().distinct().toList();
    if (distinct.isEmpty()) throw ApiException.validation("ids", "choose at least one product");
    if (distinct.size() > MAX_BULK) throw ApiException.validation("ids", "at most " + MAX_BULK + " products at a time");
    if (hotStart != null && hotEnd != null && hotEnd.isBefore(hotStart)) throw ApiException.validation("hotEnd", "must be after hotStart");
    int n = products.setHotSale(distinct, hotSale, hotStart, hotEnd, clock.instant());
    if (n != distinct.size()) throw ApiException.notFound("Product"); // rolls the whole change back
    audit.log(actor, "HOT_SALE_BULK", "product", null, (hotSale ? "on" : "off") + " for " + n + " products: " + distinct);
    changed();
    return new HotSaleResult(n, distinct);
  }

  /** The most products one bulk change may touch. */
  static final int MAX_BULK = 200;

  /** Every category including hidden ones, with the active flag and picture, in the same order as the shop list. */
  public List<AdminCategory> adminCategories() { return categories.listAll(); }

  /**
   * Deletes a category nothing refers to.
   * @throws ApiException {@code NOT_FOUND} for an unknown id; {@code 409 CATEGORY_IN_USE} when a product or coupon uses it
   */
  @Transactional
  public void deleteCategory(UUID id) {
    categories.find(id).orElseThrow(() -> ApiException.notFound("Category"));
    if (categories.inUse(id)) {
      throw ApiException.conflict("CATEGORY_IN_USE", "Products or coupons still use this category; move them or hide it instead", Map.of());
    }
    categories.delete(id);
    changed();
  }

  /** Stores the already-uploaded picture on a category; {@code NOT_FOUND} when it does not exist. */
  @Transactional
  public AdminCategory setCategoryImage(UUID id, String url) {
    categories.find(id).orElseThrow(() -> ApiException.notFound("Category"));
    categories.setImage(id, url);
    changed();
    return categories.findAdmin(id).orElseThrow();
  }

  /** Creates a category and returns it. */
  @Transactional
  public Category createCategory(String nameEn, String nameGu, int sort, boolean active) {
    UUID id = categories.insert(nameEn, nameGu, sort, active);
    changed();
    return categories.find(id).orElseThrow();
  }

  /** Creates a category and returns it as the admin list shows it (with the active flag), whether active or hidden. */
  @Transactional
  public AdminCategory createAdminCategory(String nameEn, String nameGu, int sort, boolean active) {
    UUID id = categories.insert(nameEn, nameGu, sort, active);
    changed();
    return categories.findAdmin(id).orElseThrow();
  }

  /** The category as the admin list shows it; {@code NOT_FOUND} when unknown (used to repeat an earlier create). */
  public AdminCategory adminCategory(UUID id) {
    return categories.findAdmin(id).orElseThrow(() -> ApiException.notFound("Category"));
  }

  /** Changes a category's names, order and active flag and returns it as the admin list shows it; {@code NOT_FOUND} when unknown. */
  @Transactional
  public AdminCategory updateCategory(UUID id, String nameEn, String nameGu, int sort, boolean active) {
    categories.findAdmin(id).orElseThrow(() -> ApiException.notFound("Category"));
    categories.update(id, nameEn, nameGu, sort, active);
    changed();
    return categories.findAdmin(id).orElseThrow();
  }

  /** Every hot-sale banner, including inactive and expired ones, ordered by start time. */
  public List<HotBanner> adminBanners() { return banners.all(); }

  /** Creates a hot-sale banner. A null start or end means the banner has no limit on that side. */
  @Transactional
  public HotBanner createBanner(String title, String sub, String img, Instant start, Instant end, boolean active) {
    requireWindow(start, end);
    UUID id = banners.insert(title, nz(sub), img, start, end, active);
    changed();
    return banners.find(id).orElseThrow();
  }

  /** Updates a hot-sale banner; {@code NOT_FOUND} when it does not exist. */
  @Transactional
  public HotBanner updateBanner(UUID id, String title, String sub, String img, Instant start, Instant end, boolean active) {
    requireWindow(start, end);
    banners.find(id).orElseThrow(() -> ApiException.notFound("Banner"));
    banners.update(id, title, nz(sub), img, start, end, active);
    changed();
    return banners.find(id).orElseThrow();
  }

  /** Deletes a hot-sale banner (deleting an unknown id does nothing). */
  @Transactional
  public void deleteBanner(UUID id) {
    banners.delete(id);
    changed();
  }

  /** Every variant of a product, active or not, with stock numbers. The id may be the group id or any variant's id. */
  @Transactional(readOnly = true)
  public List<AdminProduct> adminVariants(UUID idOrGroupId) {
    List<ProductData> rows = products.allMembersOf(idOrGroupId);
    if (rows.isEmpty()) throw ApiException.notFound("Product");
    return withStock(new Page<>(rows, 0, rows.size(), rows.size())).items();
  }

  /** Adds a variant to an existing product. Name, description, category and flags are copied from that product. */
  @Transactional
  public AdminProduct addVariant(UUID baseId, VariantInput in, UUID actor) {
    AdminProduct base = one(baseId);
    String name = nz(in.variantName()).trim();
    String pack = nz(in.packSize()).trim();
    if (name.isEmpty() && pack.isEmpty()) throw ApiException.validation("packSize", "give the variant a name or a pack size");
    var members = products.allMembersOf(base.productGroupId());
    var info = products.variantInfo(members.stream().map(ProductData::id).toList());
    boolean duplicate = members.stream().anyMatch(m -> nz(m.packSize()).trim().equalsIgnoreCase(pack)
        && info.getOrDefault(m.id(), new ProductRepository.VariantInfo("", 0)).name().trim().equalsIgnoreCase(name));
    if (duplicate) throw ApiException.validation("packSize", "this product already has that variant");
    int order = in.variantOrder() != null ? in.variantOrder()
        : info.values().stream().mapToInt(ProductRepository.VariantInfo::order).max().orElse(-1) + 1;
    AdminProduct created = create(new ProductInput(base.categoryId(), base.nameEn(), base.nameGu(), base.description(),
        in.mrpPaise(), in.pricePaise(), nz(in.unit(), base.unit()), pack, in.active() == null || in.active(), base.hotSale(),
        base.hotStart(), base.hotEnd(), base.earnsCoins(), base.coinRedeemable(), in.qtyOnHand(),
        in.lowThreshold() < 0 ? 5 : in.lowThreshold(), base.productGroupId()), actor);
    products.setVariant(created.id(), name, order);
    return adminGet(created.id());
  }

  /** Renames a variant or changes its place in the picker. A null value keeps what is there. */
  @Transactional
  public AdminProduct setVariant(UUID id, String variantName, Integer variantOrder, UUID actor) {
    adminGet(id);
    var current = products.variantInfo(List.of(id)).getOrDefault(id, new ProductRepository.VariantInfo("", 0));
    products.setVariant(id, variantName == null ? current.name() : variantName.trim(),
        variantOrder == null ? current.order() : variantOrder);
    audit.log(actor, "UPDATE", "product", id, "variant " + (variantName == null ? current.name() : variantName.trim()));
    changed();
    return adminGet(id);
  }

  /** Edits what every variant of a product shares, in one go. An omitted {@code active} keeps each variant's current value. */
  @Transactional
  public List<AdminProduct> updateGroup(UUID idOrGroupId, GroupInput in, UUID actor) {
    List<ProductData> rows = products.allMembersOf(idOrGroupId);
    if (rows.isEmpty()) throw ApiException.notFound("Product");
    if (in.hotStart() != null && in.hotEnd() != null && in.hotEnd().isBefore(in.hotStart())) {
      throw ApiException.validation("hotEnd", "must be after hotStart");
    }
    UUID group = rows.get(0).productGroupId();
    products.updateShared(group, in.categoryId(), in.nameEn(), in.nameGu(), nz(in.description()), Boolean.TRUE.equals(in.hotSale()),
        in.hotStart(), in.hotEnd(), in.earnsCoins() == null || in.earnsCoins(), in.coinRedeemable() == null || in.coinRedeemable(),
        in.active(), clock.instant());
    audit.log(actor, "UPDATE", "product_group", group, in.nameEn());
    changed();
    return adminVariants(group);
  }

  private static void validate(ProductInput in) {
    tooLong("nameEn", in.nameEn(), Limits.NAME);
    tooLong("nameGu", in.nameGu(), Limits.NAME);
    tooLong("description", in.description(), Limits.DESCRIPTION);
    if (in.pricePaise() > Limits.MAX_PRICE_PAISE) throw ApiException.validation("pricePaise", "must be " + Limits.MAX_PRICE_PAISE + " or less");
    if (in.mrpPaise() > Limits.MAX_PRICE_PAISE) throw ApiException.validation("mrpPaise", "must be " + Limits.MAX_PRICE_PAISE + " or less");
    if (in.pricePaise() < 0 || in.mrpPaise() < 0) throw ApiException.validation("pricePaise", "price cannot be negative");
    if (in.mrpPaise() < in.pricePaise()) throw ApiException.validation("mrpPaise", "MRP must not be below the price");
    if (in.hotStart() != null && in.hotEnd() != null && in.hotEnd().isBefore(in.hotStart())) {
      throw ApiException.validation("hotEnd", "must be after hotStart");
    }
  }

  private static void tooLong(String field, String value, int max) {
    if (value != null && value.length() > max) throw ApiException.validation(field, "at most " + max + " characters");
  }

  private static void requireWindow(Instant start, Instant end) {
    if (start != null && end != null && end.isBefore(start)) throw ApiException.validation("endAt", "must be after startAt");
  }

  private static String nz(String s) { return s == null ? "" : s; }
  private static String nz(String s, String d) { return (s == null || s.isBlank()) ? d : s; }
}
