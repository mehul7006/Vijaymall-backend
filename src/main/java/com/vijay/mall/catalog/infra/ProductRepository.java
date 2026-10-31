package com.vijay.mall.catalog.infra;

import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.Page;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
        e.packSize, e.imageUrl, e.active, e.hotSale, e.hotStart, e.hotEnd, e.earnsCoins, e.coinRedeemable);
  }

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
    StringBuilder where = new StringBuilder("where 1 = 1 ");
    Map<String, Object> params = new HashMap<>();
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

  @Transactional
  public UUID insert(UUID categoryId, String nameEn, String nameGu, String description, long mrp, long price,
                     String unit, String packSize, boolean active, boolean hotSale, Instant hotStart, Instant hotEnd,
                     boolean earns, boolean redeemable, Instant now) {
    ProductEntity e = new ProductEntity();
    e.id = UUID.randomUUID();
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
  public java.util.Set<UUID> posOnlyAmong(java.util.Collection<UUID> ids) {
    if (ids.isEmpty()) return java.util.Set.of();
    return jpa.findAllById(ids).stream().filter(e -> e.posOnly).map(e -> e.id)
        .collect(java.util.stream.Collectors.toSet());
  }

  public boolean isPosOnly(UUID id) { return jpa.findById(id).map(e -> e.posOnly).orElse(false); }

  /** English names for the given products, keyed by id. */
  @Transactional(readOnly = true)
  public Map<UUID, String> names(java.util.Collection<UUID> ids) {
    return jpa.findAllById(ids).stream().collect(java.util.stream.Collectors.toMap(e -> e.id, e -> e.nameEn));
  }
}
