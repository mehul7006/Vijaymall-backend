package com.vijay.mall.catalog.infra;

import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes products in the {@code product} table and hands them out as {@link ProductData}.
 * <p>Holds the dynamic search queries for the customer list, the admin list and the product-group list.
 */
@Repository
public class ProductRepository {
  private final ProductJpa jpa;

  @PersistenceContext
  private EntityManager em;

  ProductRepository(ProductJpa jpa) { this.jpa = jpa; }

  private static ProductData toDomain(ProductEntity e) {
    return new ProductData(e.id, e.categoryId, e.nameEn, e.nameGu, e.description, e.mrpPaise, e.pricePaise, e.unit,
        e.packSize, e.imageUrl, e.active, e.hotSale, e.hotStart, e.hotEnd, e.earnsCoins, e.coinRedeemable,
        e.productGroupId == null ? e.id : e.productGroupId, e.rating, e.reviewCount);
  }

  /** The variant name ("Mango") and display order of a product within its group. */
  public record VariantInfo(String name, int order) {}

  /** Finds a product by id, active or not. */
  public Optional<ProductData> find(UUID id) { return jpa.findById(id).map(ProductRepository::toDomain); }

  /** Products with these ids (unknown ids are skipped); the order is not guaranteed. */
  public List<ProductData> findAll(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    return jpa.findAllById(ids).stream().map(ProductRepository::toDomain).toList();
  }

  /** Search used by both the customer list (onlyActive) and the admin list. Conditions are added only when used. */
  public Page<ProductData> search(String q, UUID categoryId, boolean hotOnly, boolean onlyActive, Instant now,
                                  int page, int size) {
    return search(q, categoryId, hotOnly, onlyActive, !onlyActive, now, page, size);
  }

  /** Same search; {@code includePosOnly} also returns counter-only items (loose/weighed goods) which online shoppers never see. */
  public Page<ProductData> search(String q, UUID categoryId, boolean hotOnly, boolean onlyActive, boolean includePosOnly,
                                  Instant now, int page, int size) {
    return run(q, categoryId, hotOnly, onlyActive, includePosOnly, false, now, page, size);
  }

  /**
   * The admin product list with its extra filters. Counter-only and inactive products are included unless {@code active} says
   * otherwise.
   *
   * @param hotSale true for products flagged hot sale, false for the others, {@code null} for both (the flag only: the window is not looked at)
   * @param active true for active products, false for retired ones, {@code null} for both
   * @param lowStock true to keep only products at or below their own stock alert level
   * @param sort {@code name} (default, by English name), {@code lowStock} (emptiest first) or {@code updated} (most recently changed first)
   */
  public Page<ProductData> adminSearch(String q, UUID categoryId, Boolean hotSale, Boolean active, boolean lowStock, String sort,
                                       int page, int size) {
    Map<String, Object> params = new HashMap<>();
    StringBuilder where = conditions(q, categoryId, false, false, true, false, null, params);
    if (hotSale != null) where.append("and p.hotSale = ").append(hotSale).append(' ');
    if (active != null) where.append("and p.active = ").append(active).append(' ');
    if (lowStock) where.append("and exists (select 1 from InventoryEntity i where i.productId = p.id and i.qtyOnHand <= i.lowThreshold) ");
    String order = switch (sort == null ? "name" : sort) {
      case "lowStock" -> "order by (select i.qtyOnHand from InventoryEntity i where i.productId = p.id), p.nameEn, p.id";
      case "updated" -> "order by p.updatedAt desc, p.id";
      default -> "order by p.nameEn, p.id";
    };
    TypedQuery<ProductEntity> list = em.createQuery("select p from ProductEntity p " + where + order, ProductEntity.class);
    params.forEach(list::setParameter);
    list.setFirstResult((int) ((long) page * size)).setMaxResults(size + 1); // one extra row tells whether another page follows
    List<ProductData> fetched = list.getResultList().stream().map(ProductRepository::toDomain).toList();
    return BoundedCount.assemble(fetched, page, size, () -> boundedCount("p.id", where, params));
  }

  /**
   * Counter search: active products (counter-only ones included) whose name matches {@code q} or whose id is in
   * {@code exactIds}, those exact matches first, in name order with the id as the tie-breaker (so paging is stable). With
   * {@code since} only products updated at or after it are kept, plus those whose counter attributes were saved, or a barcode
   * added, at or after it. That is decided inside the query by sub-selects: no list of changed ids is ever sent to the database,
   * however many products changed.
   */
  public Page<ProductData> searchForCounter(String q, Collection<UUID> exactIds, Instant since, int page, int size) {
    Map<String, Object> params = new HashMap<>();
    StringBuilder where = conditions(null, null, false, true, true, false, null, params);
    boolean hasText = q != null && !q.isBlank();
    boolean hasExact = hasText && !exactIds.isEmpty();
    if (hasText) {
      where.append("and (lower(p.nameEn) like :q or lower(p.nameGu) like :q");
      params.put("q", "%" + q.trim().toLowerCase() + "%");
      if (hasExact) {
        where.append(" or p.id in :exact");
        params.put("exact", exactIds);
      }
      where.append(") ");
    }
    if (since != null) {
      where.append("and (p.updatedAt >= :since");
      params.put("since", since);
      where.append(" or p.id in (select pp.productId from ProductPosEntity pp where pp.updatedAt >= :since)");
      where.append(" or p.id in (select b.productId from ProductBarcodeEntity b where b.createdAt >= :since)");
      where.append(") ");
    }
    String order = hasExact ? "order by case when p.id in :exact then 0 else 1 end, p.nameEn, p.id" : "order by p.nameEn, p.id";
    TypedQuery<ProductEntity> list = em.createQuery("select p from ProductEntity p " + where + order, ProductEntity.class);
    params.forEach(list::setParameter);
    list.setFirstResult((int) ((long) page * size)).setMaxResults(size + 1); // one extra row tells whether another page follows
    List<ProductData> fetched = list.getResultList().stream().map(ProductRepository::toDomain).toList();
    return BoundedCount.assemble(fetched, page, size, () -> boundedCount("p.id", where, params));
  }

  /** The customer shop list: active, not counter-only, and with stock on hand. */
  public Page<ProductData> searchInStock(String q, UUID categoryId, boolean hotOnly, Instant now, int page, int size) {
    return run(q, categoryId, hotOnly, true, false, true, now, page, size);
  }

  /** The hot-sale screen list: in-stock products on hot sale now, the soonest ending window first (no end last), then by name. */
  public Page<ProductData> searchHotInStock(Instant now, int page, int size) {
    return run(null, null, true, true, false, true, now, page, size, true);
  }

  private Page<ProductData> run(String q, UUID categoryId, boolean hotOnly, boolean onlyActive, boolean includePosOnly,
                                boolean inStockOnly, Instant now, int page, int size) {
    return run(q, categoryId, hotOnly, onlyActive, includePosOnly, inStockOnly, now, page, size, false);
  }

  private Page<ProductData> run(String q, UUID categoryId, boolean hotOnly, boolean onlyActive, boolean includePosOnly,
                                boolean inStockOnly, Instant now, int page, int size, boolean hotOrder) {
    Map<String, Object> params = new HashMap<>();
    StringBuilder where = conditions(q, categoryId, hotOnly, onlyActive, includePosOnly, inStockOnly, now, params);
    TypedQuery<ProductEntity> list = em.createQuery("select p from ProductEntity p " + where
        + (hotOrder ? "order by case when p.hotEnd is null then 1 else 0 end, p.hotEnd, p.nameEn, p.id" : "order by p.nameEn"),
        ProductEntity.class);
    params.forEach(list::setParameter);
    list.setFirstResult((int) ((long) page * size)).setMaxResults(size + 1); // one extra row tells whether another page follows
    List<ProductData> fetched = list.getResultList().stream().map(ProductRepository::toDomain).toList();
    // exact total: no count when the page settles it, otherwise a count that stops at BoundedCount.CAP rows
    return BoundedCount.assemble(fetched, page, size, () -> boundedCount("p.id", where, params));
  }

  /**
   * How many rows match, counting at most {@link BoundedCount#CAP} + 1 (exact below that, never cached, and as cheap on a million
   * products as on a thousand).
   *
   * @param select what is counted: {@code p.id} for products, {@code distinct p.productGroupId} for product groups
   * @param where the filter built by {@link #conditions}
   * @param params the filter's parameters
   */
  private long boundedCount(String select, CharSequence where, Map<String, Object> params) {
    TypedQuery<Long> q = em.createQuery("select count(*) from (select " + select + " as k from ProductEntity p " + where + "order by " + select.replace("distinct ", "") + " limit "
        + (BoundedCount.CAP + 1) + ") bounded", Long.class);
    params.forEach(q::setParameter);
    return q.getSingleResult();
  }

  /** The WHERE clause shared by the product list and the product-group list; fills {@code params}. */
  private static StringBuilder conditions(String q, UUID categoryId, boolean hotOnly, boolean onlyActive,
                                          boolean includePosOnly, boolean inStockOnly, Instant now,
                                          Map<String, Object> params) {
    StringBuilder where = new StringBuilder("where 1 = 1 ");
    if (inStockOnly) where.append("and exists (select 1 from InventoryEntity i where i.productId = p.id and i.qtyOnHand > 0) ");
    if (onlyActive) where.append("and p.active = true ");
    if (!includePosOnly) where.append("and p.posOnly = false ");
    if (q != null && !q.isBlank()) {
      where.append("and (lower(p.nameEn) like :q or lower(p.nameGu) like :q) ");
      params.put("q", "%" + q.trim().toLowerCase() + "%");
    }
    if (categoryId != null) {
      where.append("and p.categoryId = :cat ");
      params.put("cat", categoryId);
    }
    if (hotOnly) {
      where.append("and p.hotSale = true and (p.hotStart is null or p.hotStart <= :now) and (p.hotEnd is null or p.hotEnd >= :now) ");
      params.put("now", now);
    }
    return where;
  }

  /**
   * One page of product groups for customers: the group ids of the visible (active, not counter-only) products that match.
   * A group matches when any of its variants does
   * (and, with inStockOnly, has stock).
   */
  public Page<UUID> searchGroups(String q, UUID categoryId, boolean hotOnly, boolean inStockOnly, Instant now, int page,
                                 int size) {
    Map<String, Object> params = new HashMap<>();
    StringBuilder where = conditions(q, categoryId, hotOnly, true, false, inStockOnly, now, params);
    TypedQuery<UUID> list = em.createQuery("select p.productGroupId from ProductEntity p " + where
        + "group by p.productGroupId order by min(p.nameEn), p.productGroupId", UUID.class);
    params.forEach(list::setParameter);
    list.setFirstResult((int) ((long) page * size)).setMaxResults(size + 1); // one extra row tells whether another page follows
    List<UUID> fetched = list.getResultList();
    return BoundedCount.assemble(fetched, page, size, () -> boundedCount("distinct p.productGroupId", where, params));
  }

  /** Visible (active, not counter-only) variants of these groups, each group's variants in display order. */
  @Transactional(readOnly = true)
  public List<ProductData> visibleMembers(Collection<UUID> groupIds) {
    if (groupIds.isEmpty()) return List.of();
    return em.createQuery("select p from ProductEntity p where p.productGroupId in :g and p.active = true and p.posOnly = false "
            + "order by p.variantOrder, p.pricePaise, p.id", ProductEntity.class)
        .setParameter("g", groupIds).getResultList().stream().map(ProductRepository::toDomain).toList();
  }

  /** Every variant, active or not, of the group that holds this id (the id may be the group id or any variant). */
  @Transactional(readOnly = true)
  public List<ProductData> allMembersOf(UUID idOrGroupId) {
    return em.createQuery("select p from ProductEntity p where p.productGroupId = :id or p.productGroupId = "
            + "(select x.productGroupId from ProductEntity x where x.id = :id) order by p.variantOrder, p.pricePaise, p.id",
            ProductEntity.class)
        .setParameter("id", idOrGroupId).getResultList().stream().map(ProductRepository::toDomain).toList();
  }

  /** Variant name and display order for these products, keyed by id. */
  @Transactional(readOnly = true)
  public Map<UUID, VariantInfo> variantInfo(Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    return jpa.findAllById(ids).stream().collect(Collectors.toMap(e -> e.id,
        e -> new VariantInfo(e.variantName == null ? "" : e.variantName, e.variantOrder)));
  }

  /** Sets the variant name (null becomes empty) and the display order of a product within its group. */
  @Transactional
  public void setVariant(UUID id, String name, int order) {
    jpa.findById(id).ifPresent(e -> {
      e.variantName = name == null ? "" : name;
      e.variantOrder = order;
    });
  }

  /** Writes the details every variant of a product shares onto all of them. Returns how many rows changed. */
  @Transactional
  public int updateShared(UUID groupId, UUID categoryId, String nameEn, String nameGu, String description, boolean hotSale,
                          Instant hotStart, Instant hotEnd, boolean earns, boolean redeemable, Boolean active, Instant now) {
    List<ProductEntity> rows = em.createQuery("select p from ProductEntity p where p.productGroupId = :g", ProductEntity.class)
        .setParameter("g", groupId).getResultList();
    rows.forEach(e -> {
      e.categoryId = categoryId;
      e.nameEn = nameEn;
      e.nameGu = nameGu;
      e.description = description;
      e.hotSale = hotSale;
      e.hotStart = hotStart;
      e.hotEnd = hotEnd;
      e.earnsCoins = earns;
      e.coinRedeemable = redeemable;
      if (active != null) e.active = active;
      e.updatedAt = now;
    });
    return rows.size();
  }

  /** Inserts a new product that starts as its own group, and returns its generated id. */
  @Transactional
  public UUID insert(UUID categoryId, String nameEn, String nameGu, String description, long mrp, long price,
                     String unit, String packSize, boolean active, boolean hotSale, Instant hotStart, Instant hotEnd,
                     boolean earns, boolean redeemable, Instant now) {
    ProductEntity e = new ProductEntity();
    e.id = UUID.randomUUID();
    e.productGroupId = e.id; // alone until an admin groups it with other packs
    e.createdAt = now;
    fill(e, categoryId, nameEn, nameGu, description, mrp, price, unit, packSize, active, hotSale, hotStart, hotEnd, earns,
        redeemable, now);
    return jpa.save(e).id;
  }

  /** Overwrites the editable fields of a product; does nothing when the id does not exist. */
  @Transactional
  public void update(UUID id, UUID categoryId, String nameEn, String nameGu, String description, long mrp, long price,
                     String unit, String packSize, boolean active, boolean hotSale, Instant hotStart, Instant hotEnd,
                     boolean earns, boolean redeemable, Instant now) {
    jpa.findById(id).ifPresent(e -> fill(e, categoryId, nameEn, nameGu, description, mrp, price, unit, packSize, active,
        hotSale, hotStart, hotEnd, earns, redeemable, now));
  }

  /** Puts the product in the same group as its other packs. */
  @Transactional
  public void setGroup(UUID id, UUID groupId) { jpa.findById(id).ifPresent(e -> e.productGroupId = groupId); }

  /** Stores the review average and count shown on product cards. */
  @Transactional
  public void setRating(UUID id, double rating, int reviewCount) {
    jpa.findById(id).ifPresent(e -> {
      e.rating = rating;
      e.reviewCount = reviewCount;
    });
  }

  /** Stores the picture path on the product and stamps {@code updatedAt}. */
  @Transactional
  public void setImage(UUID id, String url, Instant now) {
    jpa.findById(id).ifPresent(e -> {
      e.imageUrl = url;
      e.updatedAt = now;
    });
  }

  private static void fill(ProductEntity e, UUID categoryId, String nameEn, String nameGu, String description, long mrp,
                           long price, String unit, String packSize, boolean active, boolean hotSale, Instant hotStart,
                           Instant hotEnd, boolean earns, boolean redeemable, Instant now) {
    e.categoryId = categoryId;
    e.nameEn = nameEn;
    e.nameGu = nameGu;
    e.description = description;
    e.mrpPaise = mrp;
    e.pricePaise = price;
    e.unit = unit;
    e.packSize = packSize;
    e.active = active;
    e.hotSale = hotSale;
    e.hotStart = hotStart;
    e.hotEnd = hotEnd;
    e.earnsCoins = earns;
    e.coinRedeemable = redeemable;
    e.updatedAt = now;
  }

  /**
   * Sets the hot-sale flag and window of these products in one go and stamps {@code updatedAt}.
   * @return how many products were changed; ids that do not exist are skipped
   */
  @Transactional
  public int setHotSale(Collection<UUID> ids, boolean hotSale, Instant hotStart, Instant hotEnd, Instant now) {
    List<ProductEntity> found = jpa.findAllById(ids);
    found.forEach(e -> {
      e.hotSale = hotSale;
      e.hotStart = hotStart;
      e.hotEnd = hotEnd;
      e.updatedAt = now;
    });
    return found.size();
  }

  /** Switches a product on or off for sale and stamps {@code updatedAt}; does nothing when the id does not exist. */
  @Transactional
  public void setActive(UUID id, boolean active, Instant now) {
    jpa.findById(id).ifPresent(e -> {
      e.active = active;
      e.updatedAt = now;
    });
  }

  /**
   * Says what still refers to this product, or null when nothing does (so it can be deleted for good). The answer names the
   * first kind of record found: order lines, counter sale lines, purchase lines, flash-sale items, reviews, defective stock
   * or offline-till exceptions. Returns and flash-sale consumption hang off those lines, so they are covered too.
   */
  @Transactional(readOnly = true)
  public String usedBy(UUID id) {
    Object r = em.createNativeQuery("select case "
        + "when exists (select 1 from order_item where product_id = :id) then 'orders' "
        + "when exists (select 1 from pos_sale_item where product_id = :id) then 'counter sales' "
        + "when exists (select 1 from purchase_item where product_id = :id) then 'purchases' "
        + "when exists (select 1 from flash_sale_item where product_id = :id) then 'flash sales' "
        + "when exists (select 1 from product_review where product_id = :id) then 'reviews' "
        + "when exists (select 1 from defective_stock where product_id = :id) then 'defective stock records' "
        + "when exists (select 1 from pos_offline_exception where product_id = :id) then 'offline till records' "
        + "else null end").setParameter("id", id).getSingleResult();
    return (String) r;
  }

  /**
   * Removes a product row together with its inventory row, stock movements, counter attributes and barcodes, wish-list and
   * cart rows. Call only when {@link #usedBy} returned null; the caller runs it inside one transaction.
   */
  @Transactional
  public void purge(UUID id) {
    em.flush();
    for (String table : List.of("cart_item", "wishlist_item", "product_barcode", "product_pos", "stock_movement", "inventory")) {
      em.createNativeQuery("delete from " + table + " where product_id = :id").setParameter("id", id).executeUpdate();
    }
    em.createNativeQuery("delete from product where id = :id").setParameter("id", id).executeUpdate();
    em.clear();
  }

  /** Sets whether the product is sold at the counter only. */
  @Transactional
  public void setPosOnly(UUID id, boolean posOnly) { jpa.findById(id).ifPresent(e -> e.posOnly = posOnly); }

  /** Which of the given products are sold at the counter only. */
  @Transactional(readOnly = true)
  public Set<UUID> posOnlyAmong(Collection<UUID> ids) {
    if (ids.isEmpty()) return Set.of();
    return jpa.findAllById(ids).stream().filter(e -> e.posOnly).map(e -> e.id)
        .collect(Collectors.toSet());
  }

  /** True when the product is counter-only; false when it is not or does not exist. */
  public boolean isPosOnly(UUID id) { return jpa.findById(id).map(e -> e.posOnly).orElse(false); }

  /** When any product (price, name, active...) last changed; null for an empty shop. */
  public Instant lastChange() { return jpa.lastChange(); }

  /** English names of the products that are still on sale, keyed by id (retired products are left out). */
  @Transactional(readOnly = true)
  public Map<UUID, String> activeNames(Collection<UUID> ids) {
    return jpa.findAllById(ids).stream().filter(e -> e.active).collect(Collectors.toMap(e -> e.id, e -> e.nameEn));
  }

  /** English names for the given products, keyed by id. */
  @Transactional(readOnly = true)
  public Map<UUID, String> names(Collection<UUID> ids) {
    return jpa.findAllById(ids).stream().collect(Collectors.toMap(e -> e.id, e -> e.nameEn));
  }
}
