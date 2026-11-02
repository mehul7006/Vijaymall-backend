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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  /** One row of the admin stock report. packLabel is the pack size (or unit); cashierId is whoever made the change. */
  public record StockMovementView(UUID id, UUID productId, String name, String packLabel, int delta, String reason,
                                  Instant at, UUID cashierId) {}

  public record HotSale(List<HotBanner> banners, List<Product> products) {}

  private final ProductRepository products;
  private final CategoryRepository categories;
  private final BannerRepository banners;
  private final InventoryService inventory;
  private final AuditService audit;
  private final Clock clock;

  public CatalogService(ProductRepository products, CategoryRepository categories, BannerRepository banners,
                        InventoryService inventory, AuditService audit, Clock clock) {
    this.products = products;
    this.categories = categories;
    this.banners = banners;
    this.inventory = inventory;
    this.audit = audit;
    this.clock = clock;
  }

  // ---------- customer side ----------
  public java.util.Map<UUID, String> names(java.util.Collection<UUID> ids) { return products.names(ids); }

  public List<Category> categories() { return categories.listActive(); }

  public Page<Product> list(String q, UUID categoryId, boolean hot, int page, int size) {
    Instant now = clock.instant();
    Page<AdminProduct> p = withStock(products.search(q, categoryId, hot, true, now, Math.max(0, page), Page.clampSize(size)));
    return new Page<>(p.items().stream().map(a -> a.toCustomer(now)).toList(), p.page(), p.size(), p.total());
  }

  public Product get(UUID id) {
    AdminProduct p = one(id);
    if (!p.active() || products.isPosOnly(id)) throw ApiException.notFound("Product");
    return p.toCustomer(clock.instant());
  }

  public HotSale hotSale() {
    Instant now = clock.instant();
    List<Product> list = withStock(products.search(null, null, true, true, now, 0, 20)).items().stream()
        .map(a -> a.toCustomer(now)).toList();
    return new HotSale(banners.live(now), list);
  }

  /** Prices a cart from the current catalog. Unknown or inactive products are rejected. */
  public List<PricedLine> price(List<ItemRequest> items) {
    if (items == null || items.isEmpty()) throw ApiException.validation("items", "cart is empty");
    items.forEach(it -> {
      if (it.qty() <= 0 || it.qty() > 99) throw ApiException.validation("qty", "quantity must be 1 to 99");
    });
    Map<UUID, Integer> merged = items.stream().collect(
        java.util.stream.Collectors.toMap(ItemRequest::productId, ItemRequest::qty, Integer::sum, LinkedHashMap::new));
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
    return ids.stream().map(byId::get)
        .filter(p -> p != null && p.active() && !counterOnly.contains(p.id()))
        .map(p -> p.withStock(stocks.get(p.id()).qty(), stocks.get(p.id()).low()).toCustomer(now)).toList();
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
    List<StockMovementView> items = rows.items().stream().map(m -> {
      ProductData p = byId.get(m.productId());
      return new StockMovementView(m.id(), m.productId(), p == null ? "?" : p.nameEn(), p == null ? "" : p.unitLabel(),
          m.delta(), m.reason(), m.at(), m.actorId());
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

  @Transactional
  public AdminProduct create(ProductInput in, UUID actor) {
    validate(in);
    UUID id = products.insert(in.categoryId(), in.nameEn(), in.nameGu(), nz(in.description()), in.mrpPaise(),
        in.pricePaise(), nz(in.unit(), "pc"), nz(in.packSize()), in.active(), in.hotSale(), in.hotStart(),
        in.hotEnd(), in.earnsCoins(), in.coinRedeemable(), clock.instant());
    if (in.productGroupId() != null) products.setGroup(id, in.productGroupId());
    inventory.create(id, Math.max(0, in.qtyOnHand()), in.lowThreshold() <= 0 ? 5 : in.lowThreshold());
    audit.log(actor, "CREATE", "product", id, in.nameEn() + " @ " + in.pricePaise());
    return adminGet(id);
  }

  @Transactional
  public AdminProduct update(UUID id, ProductInput in, UUID actor) {
    validate(in);
    AdminProduct before = adminGet(id);
    products.update(id, in.categoryId(), in.nameEn(), in.nameGu(), nz(in.description()), in.mrpPaise(),
        in.pricePaise(), nz(in.unit(), "pc"), nz(in.packSize()), in.active(), in.hotSale(), in.hotStart(),
        in.hotEnd(), in.earnsCoins(), in.coinRedeemable(), clock.instant());
    if (in.productGroupId() != null) products.setGroup(id, in.productGroupId());
    int delta = in.qtyOnHand() - before.qtyOnHand();
    if (delta != 0 && in.qtyOnHand() >= 0) inventory.adjust(id, delta, "ADJUST", actor);
    if (in.lowThreshold() > 0) inventory.setLowThreshold(id, in.lowThreshold());
    audit.log(actor, "UPDATE", "product", id, "price " + before.pricePaise() + " -> " + in.pricePaise());
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
    return adminGet(id);
  }

  @Transactional
  public Category createCategory(String nameEn, String nameGu, int sort, boolean active) {
    UUID id = categories.insert(nameEn, nameGu, sort, active);
    return categories.find(id).orElseThrow();
  }

  @Transactional
  public Category updateCategory(UUID id, String nameEn, String nameGu, int sort, boolean active) {
    categories.find(id).orElseThrow(() -> ApiException.notFound("Category"));
    categories.update(id, nameEn, nameGu, sort, active);
    return categories.find(id).orElseThrow();
  }

  public List<HotBanner> adminBanners() { return banners.all(); }

  @Transactional
  public HotBanner createBanner(String title, String sub, String img, Instant start, Instant end, boolean active) {
    UUID id = banners.insert(title, nz(sub), img, start, end, active);
    return banners.find(id).orElseThrow();
  }

  @Transactional
  public HotBanner updateBanner(UUID id, String title, String sub, String img, Instant start, Instant end, boolean active) {
    banners.find(id).orElseThrow(() -> ApiException.notFound("Banner"));
    banners.update(id, title, nz(sub), img, start, end, active);
    return banners.find(id).orElseThrow();
  }

  @Transactional
  public void deleteBanner(UUID id) { banners.delete(id); }

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
