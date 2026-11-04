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

interface PosReturnJpa extends JpaRepository<PosReturnEntity, UUID> {
  Optional<PosReturnEntity> findByIdemKey(String idemKey);

  List<PosReturnEntity> findBySaleIdOrderByCreatedAt(UUID saleId);

  @Query("select r.id, r.creditNoteNo from PosReturnEntity r where r.id in :ids")
  List<Object[]> creditNoteNumbers(@Param("ids") Collection<UUID> ids);
}
