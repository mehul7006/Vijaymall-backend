package com.vijay.mall.catalog.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the {@code category} table. */
interface CategoryJpa extends JpaRepository<CategoryEntity, UUID> {
  /** Active categories in display order (sort order, then English name). */
  List<CategoryEntity> findByActiveTrueOrderBySortOrderAscNameEnAsc();

  /** Every category, active or not, in display order (sort order, then English name). */
  List<CategoryEntity> findAllByOrderBySortOrderAscNameEnAsc();
}
