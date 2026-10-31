package com.vijay.mall.catalog.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CategoryJpa extends JpaRepository<CategoryEntity, UUID> {
  List<CategoryEntity> findByActiveTrueOrderBySortOrderAscNameEnAsc();
}
