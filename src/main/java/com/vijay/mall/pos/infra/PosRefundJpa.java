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

/** Spring Data access to {@code pos_refund_payment}, how credit notes were paid out. */
interface PosRefundJpa extends JpaRepository<PosRefundPaymentEntity, UUID> {
  List<PosRefundPaymentEntity> findByReturnIdIn(Collection<UUID> returnIds);
}
