package com.vijay.mall.catalog.infra;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the {@code unit_of_measure} table. */
interface UnitJpa extends JpaRepository<UnitEntity, String> {
  /** Every unit in display order (sort order, then code). */
  List<UnitEntity> findAllByOrderBySortOrderAscCodeAsc();

  /** Active units in display order. */
  List<UnitEntity> findByActiveTrueOrderBySortOrderAscCodeAsc();

  /** A unit by code, ignoring upper and lower case. */
  Optional<UnitEntity> findByCodeIgnoreCase(String code);
}
