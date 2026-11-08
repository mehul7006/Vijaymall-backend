package com.vijay.mall.pos.infra;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface PosShiftJpa extends JpaRepository<PosShiftEntity, UUID> {
  Optional<PosShiftEntity> findFirstByCashierIdAndClosedAtIsNull(UUID cashierId);

  Page<PosShiftEntity> findAllByOrderByOpenedAtDesc(Pageable page);

  Page<PosShiftEntity> findByCashierIdOrderByOpenedAtDesc(UUID cashierId, Pageable page);
}
