package com.vijay.mall.order.infra;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderJpa extends JpaRepository<OrderEntity, UUID> {
  Optional<OrderEntity> findByUserIdAndIdemKey(UUID userId, String idemKey);

  Page<OrderEntity> findByUserId(UUID userId, Pageable page);

  Page<OrderEntity> findByStatus(String status, Pageable page);

  long countByUserIdAndStatusNot(UUID userId, String status);

  @Query("select o from OrderEntity o where o.status = 'PAYMENT_PENDING' and o.expiresAt < :now order by o.expiresAt")
  List<OrderEntity> expiredPending(@Param("now") Instant now, Pageable page);

  @Query("select o.id, o.orderNo from OrderEntity o where o.id in :ids")
  List<Object[]> numbers(@Param("ids") Collection<UUID> ids);
}
