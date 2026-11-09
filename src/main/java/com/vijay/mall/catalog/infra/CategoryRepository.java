package com.vijay.mall.catalog.infra;

import com.vijay.mall.catalog.domain.AdminCategory;
import com.vijay.mall.catalog.domain.Category;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores shop categories in the {@code category} table and hands them out as {@link Category} records. */
@Repository
public class CategoryRepository {
  private final CategoryJpa jpa;

  @PersistenceContext
  private EntityManager em;

  CategoryRepository(CategoryJpa jpa) { this.jpa = jpa; }

  private static AdminCategory toAdmin(CategoryEntity e) {
    return new AdminCategory(e.id, e.nameEn, e.nameGu, e.imageUrl, e.sortOrder, e.active);
  }

  /** Every category including inactive ones, in the same order as the shop list. */
  public List<AdminCategory> listAll() {
    return jpa.findAllByOrderBySortOrderAscNameEnAsc().stream().map(CategoryRepository::toAdmin).toList();
  }

  /** Finds a category with its active flag; empty when unknown. */
  public Optional<AdminCategory> findAdmin(UUID id) { return jpa.findById(id).map(CategoryRepository::toAdmin); }

  /** Stores the picture path of a category; does nothing when the id does not exist. */
  @Transactional
  public void setImage(UUID id, String url) { jpa.findById(id).ifPresent(e -> e.imageUrl = url); }

  /** True when any product or coupon still refers to the category. */
  @Transactional(readOnly = true)
  public boolean inUse(UUID id) {
    return (boolean) em.createNativeQuery("select exists (select 1 from product where category_id = :id) "
        + "or exists (select 1 from coupon where category_id = :id) "
        + "or exists (select 1 from coupon_category where category_id = :id)").setParameter("id", id).getSingleResult();
  }

  /** Deletes a category row; call only when {@link #inUse} is false. */
  @Transactional
  public void delete(UUID id) { jpa.deleteById(id); }

  private static Category toDomain(CategoryEntity e) { return new Category(e.id, e.nameEn, e.nameGu, e.imageUrl, e.sortOrder); }

  /** Active categories in display order (sort order, then English name). */
  public List<Category> listActive() {
    return jpa.findByActiveTrueOrderBySortOrderAscNameEnAsc().stream().map(CategoryRepository::toDomain).toList();
  }

  /** Finds a category by id, active or not. */
  public Optional<Category> find(UUID id) { return jpa.findById(id).map(CategoryRepository::toDomain); }

  /** Saves a new category and returns its generated id. */
  @Transactional
  public UUID insert(String nameEn, String nameGu, int sortOrder, boolean active) {
    CategoryEntity e = new CategoryEntity();
    e.id = UUID.randomUUID();
    e.nameEn = nameEn;
    e.nameGu = nameGu;
    e.sortOrder = sortOrder;
    e.active = active;
    return jpa.save(e).id;
  }

  /** Overwrites the names, sort order and active flag; does nothing when the id does not exist. */
  @Transactional
  public void update(UUID id, String nameEn, String nameGu, int sortOrder, boolean active) {
    jpa.findById(id).ifPresent(e -> {
      e.nameEn = nameEn;
      e.nameGu = nameGu;
      e.sortOrder = sortOrder;
      e.active = active;
    });
  }
}
