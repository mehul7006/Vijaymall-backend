package com.vijay.mall.order.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderReturnJpa extends JpaRepository<OrderReturnEntity, UUID> {
  Optional<OrderReturnEntity> findByIdemKey(String idemKey);

  List<OrderReturnEntity> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

  @Query("select coalesce(sum(r.refundPaise), 0), coalesce(sum(r.coinsClawedBack), 0), coalesce(sum(r.coinsRestored), 0) "
      + "from OrderReturnEntity r where r.orderId = :order")
  List<Object[]> totalsFor(@Param("order") UUID orderId);
}
