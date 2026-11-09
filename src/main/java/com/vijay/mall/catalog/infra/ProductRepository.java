package com.vijay.mall.catalog.infra;

import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.Page;
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

  public Optional<ProductData> find(UUID id) { return jpa.findById(id).map(ProductRepository::toDomain); }

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

  /** The customer shop list: active, not counter-only, and with stock on hand. */
  public Page<ProductData> searchInStock(String q, UUID categoryId, boolean hotOnly, Instant now, int page, int size) {
    return run(q, categoryId, hotOnly, true, false, true, now, page, size);
  }

  private Page<ProductData> run(String q, UUID categoryId, boolean hotOnly, boolean onlyActive, boolean includePosOnly,
                                boolean inStockOnly, Instant now, int page, int size) {
    Map<String, Object> params = new HashMap<>();
    StringBuilder where = conditions(q, categoryId, hotOnly, onlyActive, includePosOnly, inStockOnly, now, params);
    TypedQuery<Long> count = em.createQuery("select count(p) from ProductEntity p " + where, Long.class);
    TypedQuery<ProductEntity> list = em.createQuery("select p from ProductEntity p " + where + "order by p.nameEn", ProductEntity.class);
    params.forEach((k, v) -> {
      count.setParameter(k, v);
      list.setParameter(k, v);
    });
    list.setFirstResult((int) ((long) page * size)).setMaxResults(size);
    List<ProductData> items = list.getResultList().stream().map(ProductRepository::toDomain).toList();
    return new Page<>(items, page, size, count.getSingleResult());
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
    TypedQuery<Long> count = em.createQuery("select count(distinct p.productGroupId) from ProductEntity p " + where, Long.class);
    TypedQuery<UUID> list = em.createQuery("select p.productGroupId from ProductEntity p " + where
        + "group by p.productGroupId order by min(p.nameEn), p.productGroupId", UUID.class);
    params.forEach((k, v) -> {
      count.setParameter(k, v);
      list.setParameter(k, v);
    });
    list.setFirstResult((int) ((long) page * size)).setMaxResults(size);
    return new Page<>(list.getResultList(), page, size, count.getSingleResult());
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

  @Transactional(readOnly = true)
  public Map<UUID, VariantInfo> variantInfo(Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    return jpa.findAllById(ids).stream().collect(Collectors.toMap(e -> e.id,
        e -> new VariantInfo(e.variantName == null ? "" : e.variantName, e.variantOrder)));
  }

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

  @Transactional
  public void setPosOnly(UUID id, boolean posOnly) { jpa.findById(id).ifPresent(e -> e.posOnly = posOnly); }

  /** Which of the given products are sold at the counter only. */
  @Transactional(readOnly = true)
  public Set<UUID> posOnlyAmong(Collection<UUID> ids) {
    if (ids.isEmpty()) return Set.of();
    return jpa.findAllById(ids).stream().filter(e -> e.posOnly).map(e -> e.id)
        .collect(Collectors.toSet());
  }

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
