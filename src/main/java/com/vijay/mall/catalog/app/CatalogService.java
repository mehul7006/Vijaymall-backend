package com.vijay.mall.catalog.app;

import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.catalog.domain.Category;
import com.vijay.mall.catalog.domain.HotBanner;
import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.catalog.infra.BannerRepository;
import com.vijay.mall.catalog.infra.CategoryRepository;
import com.vijay.mall.catalog.infra.ProductRepository;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
  public record ItemRequest(UUID productId, int qty) {}

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

  public record HotSale(List<HotBanner> banners, List<Product> products) {}

  /**
   * One sellable variant of a product (for example "Mango, 500 g"). id is what the cart, the order and the review use.
   * label is the text to show next to the price: the pack size, or the unit when there is no pack size.
   */
  public record Variant(UUID id, String variantName, String packSize, String unit, String label, long mrpPaise,
                        long pricePaise, String imageUrl, boolean inStock, int qtyAvailable,
                        com.vijay.mall.common.FlashPricing.Offer flash) {
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
                             List<Variant> variants, com.vijay.mall.common.FlashPricing.Offer flash) {
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
  private final com.vijay.mall.common.ReferenceNumbers references;
  private final UnitService units;

  private com.vijay.mall.common.live.LiveEvents live;

  /** Optional: tells open app screens about changes (not provided in plain unit tests). */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setLive(com.vijay.mall.common.live.LiveEvents live) { this.live = live; }

  private void changed() { if (live != null) live.catalog(); }

  private com.vijay.mall.common.FlashPricing flash;

  /** Optional (plain unit tests run without it): shows the flash sale price on products. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setFlash(com.vijay.mall.common.FlashPricing flash) { this.flash = flash; }

  private List<Product> flashed(List<Product> list) {
    if (flash == null || list.isEmpty()) return list;
    Map<UUID, Long> prices = new LinkedHashMap<>();
    list.forEach(p -> prices.put(p.id(), p.pricePaise()));
    Map<UUID, com.vijay.mall.common.FlashPricing.Offer> offers = flash.offers(prices);
    return offers.isEmpty() ? list : list.stream().map(p -> offers.containsKey(p.id()) ? p.withFlash(offers.get(p.id())) : p).toList();
  }

  private ProductGroup flashed(ProductGroup g) {
    if (flash == null) return g;
    Map<UUID, Long> prices = new LinkedHashMap<>();
    g.variants().forEach(v -> prices.put(v.id(), v.pricePaise()));
    Map<UUID, com.vijay.mall.common.FlashPricing.Offer> offers = flash.offers(prices);
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
                        com.vijay.mall.common.ReferenceNumbers references) {
    this(products, categories, banners, inventory, audit, clock, references, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public CatalogService(ProductRepository products, CategoryRepository categories, BannerRepository banners,
                        InventoryService inventory, AuditService audit, Clock clock,
                        com.vijay.mall.common.ReferenceNumbers references, UnitService units) {
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
  public java.util.Map<UUID, String> names(java.util.Collection<UUID> ids) { return products.names(ids); }

  /** When any product last changed (the offline till compares this with the copy it holds). */
  public java.time.Instant lastChange() { return products.lastChange(); }

  /** Names of products still on sale; stock alerts ignore retired products. */
  public java.util.Map<UUID, String> activeNames(java.util.Collection<UUID> ids) { return products.activeNames(ids); }

  private com.vijay.mall.common.cache.AppCaches caches;

  /** Optional (plain unit tests run without it): the category list rarely changes and is read on every screen. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setCaches(com.vijay.mall.common.cache.AppCaches caches) { this.caches = caches; }

  public List<Category> categories() {
    return caches == null ? categories.listActive()
        : caches.get(com.vijay.mall.common.cache.AppCaches.Name.CATEGORIES, "active", categories::listActive);
  }

  /** The shop list. Products with no stock are left out; use the overload to include them. */
  public Page<Product> list(String q, UUID categoryId, boolean hot, int page, int size) {
    return list(q, categoryId, hot, false, page, size);
  }

  public Page<Product> list(String q, UUID categoryId, boolean hot, boolean includeOutOfStock, int page, int size) {
    Instant now = clock.instant();
    int pg = Math.max(0, page);
    int sz = Page.clampSize(size);
    Page<AdminProduct> p = withStock(includeOutOfStock ? products.search(q, categoryId, hot, true, now, pg, sz)
        : products.searchInStock(q, categoryId, hot, now, pg, sz));
    return new Page<>(flashed(p.items().stream().map(a -> a.toCustomer(now)).toList()), p.page(), p.size(), p.total());
  }

  public Product get(UUID id) {
    AdminProduct p = one(id);
    if (!p.active() || products.isPosOnly(id)) throw ApiException.notFound("Product");
    return flashed(List.of(p.toCustomer(clock.instant()))).get(0);
  }

  public HotSale hotSale() {
    Instant now = clock.instant();
    List<Product> list = flashed(withStock(products.searchInStock(null, null, true, now, 0, 20)).items().stream()
        .map(a -> a.toCustomer(now)).toList());
    // all banners are cached (they change rarely); which ones are on right now depends on the clock, so that is decided here
    List<HotBanner> all = caches == null ? banners.all() : caches.get(com.vijay.mall.common.cache.AppCaches.Name.BANNERS, "all", banners::all);
    return new HotSale(all.stream().filter(b -> b.active() && (b.startAt() == null || !b.startAt().isAfter(now))
        && (b.endAt() == null || !b.endAt().isBefore(now))).toList(), list);
  }

  /** Products with their variants, one entry per product. A product matches when any of its variants does. */
  public Page<ProductGroup> groups(String q, UUID categoryId, boolean hot, int page, int size) {
    return groups(q, categoryId, hot, false, page, size);
  }

  /** With includeOutOfStock false (the default) products with no stock are left out, and so are a product's sold-out variants. */
  @Transactional(readOnly = true)
  public Page<ProductGroup> groups(String q, UUID categoryId, boolean hot, boolean includeOutOfStock, int page, int size) {
    Instant now = clock.instant();
    Page<UUID> ids = products.searchGroups(q, categoryId, hot, !includeOutOfStock, now, Math.max(0, page), Page.clampSize(size));
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
    return new Page<>(items, ids.page(), ids.size(), ids.total());
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
                                      Map<UUID, com.vijay.mall.inventory.infra.InventoryRepository.Stock> stocks,
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
    ProductData shared = sorted.get(0);
    int reviews = sorted.stream().mapToInt(ProductData::reviewCount).sum();
    double weighted = sorted.stream().mapToDouble(p -> p.rating() * p.reviewCount()).sum();
    double rating = reviews == 0 ? 0 : Math.round(weighted / reviews * 100.0) / 100.0;
    String image = def.imageUrl() != null ? def.imageUrl()
        : variants.stream().map(Variant::imageUrl).filter(u -> u != null).findFirst().orElse(null);
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
      if (it.qty() <= 0 || it.qty() > com.vijay.mall.common.OrderLimits.MAX_LINE_QTY) {
        throw ApiException.validation("qty", "quantity must be 1 to " + com.vijay.mall.common.OrderLimits.MAX_LINE_QTY);
      }
    });
    Map<UUID, Integer> merged = items.stream().collect(
        java.util.stream.Collectors.toMap(ItemRequest::productId, ItemRequest::qty, Integer::sum, LinkedHashMap::new));
    // the same product listed twice counts once, so the limit applies to the total
    if (merged.values().stream().anyMatch(q -> q > com.vijay.mall.common.OrderLimits.MAX_LINE_QTY)) {
      throw ApiException.validation("qty", "quantity must be 1 to " + com.vijay.mall.common.OrderLimits.MAX_LINE_QTY);
    }
    Map<UUID, ProductData> byId = products.findAll(List.copyOf(merged.keySet())).stream()
        .collect(java.util.stream.Collectors.toMap(ProductData::id, java.util.function.Function.identity()));
    java.util.Set<UUID> counterOnly = products.posOnlyAmong(merged.keySet());
    return merged.entrySet().stream().map(e -> {
      ProductData p = byId.get(e.getKey());
      if (p == null || !p.active() || counterOnly.contains(p.id())) throw ApiException.validation("productId", "product is not available: " + e.getKey());
      return new PricedLine(p.id(), p.categoryId(), p.nameEn(), e.getValue(), p.pricePaise(),
          p.pricePaise() * e.getValue(), p.coinRedeemable(), p.earnsCoins(), p.nameGu(), p.unitLabel());
    }).toList();
  }

  /** Customer view of these products in the same order, leaving out any that are inactive or counter-only. */
  public List<Product> visible(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    Instant now = clock.instant();
    var stocks = inventory.stocks(ids);
    java.util.Set<UUID> counterOnly = products.posOnlyAmong(ids);
    Map<UUID, ProductData> byId = products.findAll(ids).stream()
        .collect(java.util.stream.Collectors.toMap(ProductData::id, java.util.function.Function.identity()));
    return flashed(ids.stream().map(byId::get)
        .filter(p -> p != null && p.active() && !counterOnly.contains(p.id()))
        .map(p -> p.withStock(stocks.get(p.id()).qty(), stocks.get(p.id()).low()).toCustomer(now)).toList());
  }

  /** Review average and count, written by the review service after every change. */
  @Transactional
  public void updateRating(UUID productId, double rating, int reviewCount) {
    products.setRating(productId, rating, reviewCount);
  }

  // ---------- counter (POS) side ----------
  /** Active products for the counter, including counter-only items. */
  public Page<ProductData> posSearch(String q, int page, int size) {
    return products.search(q, null, false, true, true, clock.instant(), Math.max(0, page), Page.clampSize(size));
  }

  public List<ProductData> posProducts(java.util.Collection<UUID> ids) { return products.findAll(List.copyOf(ids)); }

  @Transactional
  public void setPosOnly(UUID id, boolean posOnly) {
    adminGet(id);
    products.setPosOnly(id, posOnly);
    changed();
  }

  // ---------- admin side ----------
  public Page<AdminProduct> adminList(String q, UUID categoryId, int page, int size) {
    return withStock(products.search(q, categoryId, false, false, clock.instant(), Math.max(0, page), Page.clampSize(size)));
  }

  public AdminProduct adminGet(UUID id) { return one(id); }

  /** Stock history with product names, newest first. productId null means all products. */
  public Page<StockMovementView> stockMovements(UUID productId, int page, int size) {
    Page<com.vijay.mall.inventory.infra.InventoryRepository.Movement> rows = inventory.movements(productId, page, size);
    Map<UUID, ProductData> byId = products.findAll(rows.items().stream().map(m -> m.productId()).distinct().toList())
        .stream().collect(java.util.stream.Collectors.toMap(ProductData::id, java.util.function.Function.identity()));
    Map<UUID, String> numbers = references.of(rows.items().stream().map(m -> m.refId()).filter(java.util.Objects::nonNull)
        .distinct().toList());
    List<StockMovementView> items = rows.items().stream().map(m -> {
      ProductData p = byId.get(m.productId());
      return new StockMovementView(m.id(), m.productId(), p == null ? "?" : p.nameEn(), p == null ? "" : p.unitLabel(),
          m.delta(), m.reason(), m.at(), m.actorId(), m.refId(), m.refId() == null ? null : numbers.get(m.refId()));
    }).toList();
    return new Page<>(items, rows.page(), rows.size(), rows.total());
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
    return new Page<>(items, page.page(), page.size(), page.total());
  }

  /** The unit to store: checked against the admin's unit list when there is one, else the text as given. */
  private String unit(String given, String current) {
    if (units != null) return units.canonicalOrKeep(given, current);
    return nz(given, current == null ? "pc" : current);
  }

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

  @Transactional
  public AdminProduct restock(UUID id, int delta, String reason, UUID actor) {
    adminGet(id);
    inventory.adjust(id, delta, "ADJUST".equals(reason) ? "ADJUST" : "RESTOCK", actor);
    audit.log(actor, "STOCK", "product", id, "delta " + delta);
    return adminGet(id);
  }

  @Transactional
  public AdminProduct setImage(UUID id, String url) {
    adminGet(id);
    products.setImage(id, url, clock.instant());
    changed();
    return adminGet(id);
  }

  @Transactional
  public Category createCategory(String nameEn, String nameGu, int sort, boolean active) {
    UUID id = categories.insert(nameEn, nameGu, sort, active);
    changed();
    return categories.find(id).orElseThrow();
  }

  @Transactional
  public Category updateCategory(UUID id, String nameEn, String nameGu, int sort, boolean active) {
    categories.find(id).orElseThrow(() -> ApiException.notFound("Category"));
    categories.update(id, nameEn, nameGu, sort, active);
    changed();
    return categories.find(id).orElseThrow();
  }

  public List<HotBanner> adminBanners() { return banners.all(); }

  @Transactional
  public HotBanner createBanner(String title, String sub, String img, Instant start, Instant end, boolean active) {
    UUID id = banners.insert(title, nz(sub), img, start, end, active);
    changed();
    return banners.find(id).orElseThrow();
  }

  @Transactional
  public HotBanner updateBanner(UUID id, String title, String sub, String img, Instant start, Instant end, boolean active) {
    banners.find(id).orElseThrow(() -> ApiException.notFound("Banner"));
    banners.update(id, title, nz(sub), img, start, end, active);
    changed();
    return banners.find(id).orElseThrow();
  }

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

  /** Edits what every variant of a product shares, in one go. */
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
    if (in.pricePaise() < 0 || in.mrpPaise() < 0) throw ApiException.validation("pricePaise", "price cannot be negative");
    if (in.mrpPaise() < in.pricePaise()) throw ApiException.validation("mrpPaise", "MRP must not be below the price");
    if (in.hotStart() != null && in.hotEnd() != null && in.hotEnd().isBefore(in.hotStart())) {
      throw ApiException.validation("hotEnd", "must be after hotStart");
    }
  }

  private static String nz(String s) { return s == null ? "" : s; }
  private static String nz(String s, String d) { return (s == null || s.isBlank()) ? d : s; }
}
