package com.vijay.mall.order.infra;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderItemJpa extends JpaRepository<OrderItemEntity, UUID> {
  List<OrderItemEntity> findByOrderIdOrderByName(UUID orderId);

  @Query("select i.orderId, sum(i.qty) from OrderItemEntity i where i.orderId in :ids group by i.orderId")
  List<Object[]> countsFor(@Param("ids") Collection<UUID> orderIds);
}
