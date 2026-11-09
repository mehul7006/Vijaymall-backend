package com.vijay.mall.catalog.infra;

import com.vijay.mall.catalog.domain.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores shop categories in the {@code category} table and hands them out as {@link Category} records. */
@Repository
public class CategoryRepository {
  private final CategoryJpa jpa;

  CategoryRepository(CategoryJpa jpa) { this.jpa = jpa; }

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
