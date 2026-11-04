package com.vijay.mall.catalog.infra;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface UnitJpa extends JpaRepository<UnitEntity, String> {
  List<UnitEntity> findAllByOrderBySortOrderAscCodeAsc();

  List<UnitEntity> findByActiveTrueOrderBySortOrderAscCodeAsc();

  Optional<UnitEntity> findByCodeIgnoreCase(String code);
}
