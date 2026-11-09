package com.vijay.mall.order.infra;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderItemJpa extends JpaRepository<OrderItemEntity, UUID> {
  List<OrderItemEntity> findByOrderIdOrderByName(UUID orderId);

  /** Adds to the returned count only while it cannot pass what was sold; 0 rows means someone returned it first. */
  @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update OrderItemEntity i set i.returnedQty = i.returnedQty + :q where i.id = :id and i.returnedQty + :q <= i.qty")
  int addReturned(@Param("id") UUID id, @Param("q") int q);

  @Query("select i.orderId, sum(i.qty) from OrderItemEntity i where i.orderId in :ids group by i.orderId")
  List<Object[]> countsFor(@Param("ids") Collection<UUID> orderIds);
}
