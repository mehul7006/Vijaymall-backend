package com.vijay.mall.catalog.infra;

import com.vijay.mall.catalog.domain.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CategoryRepository {
  private final CategoryJpa jpa;

  CategoryRepository(CategoryJpa jpa) { this.jpa = jpa; }

  private static Category toDomain(CategoryEntity e) { return new Category(e.id, e.nameEn, e.nameGu, e.imageUrl, e.sortOrder); }

  public List<Category> listActive() {
    return jpa.findByActiveTrueOrderBySortOrderAscNameEnAsc().stream().map(CategoryRepository::toDomain).toList();
  }

  public Optional<Category> find(UUID id) { return jpa.findById(id).map(CategoryRepository::toDomain); }

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
