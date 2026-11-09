package com.vijay.mall.pos.infra;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code pos_sale_item}, the lines of counter bills. */
interface PosSaleItemJpa extends JpaRepository<PosSaleItemEntity, UUID> {
  List<PosSaleItemEntity> findBySaleId(UUID saleId);

  List<PosSaleItemEntity> findBySaleIdIn(Collection<UUID> saleIds);

  /** Atomic: counts returned units only while they do not exceed what was sold. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update PosSaleItemEntity i set i.returnedQty = i.returnedQty + :q where i.id = :id and i.returnedQty + :q <= i.qty")
  int addReturned(@Param("id") UUID id, @Param("q") int qty);
}
