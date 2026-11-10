package com.vijay.mall.order.infra;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code order_item}. */
interface OrderItemJpa extends JpaRepository<OrderItemEntity, UUID> {
  List<OrderItemEntity> findByOrderIdOrderByName(UUID orderId);

  /** Adds to the returned count only while it cannot pass what was sold; 0 rows means someone returned it first. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update OrderItemEntity i set i.returnedQty = i.returnedQty + :q where i.id = :id and i.returnedQty + :q <= i.qty")
  int addReturned(@Param("id") UUID id, @Param("q") int q);

  @Query("select i.orderId, sum(i.qty) from OrderItemEntity i where i.orderId in :ids group by i.orderId")
  List<Object[]> countsFor(@Param("ids") Collection<UUID> orderIds);

  /**
   * GST of the orders' lines grouped by order and rate: taxable, CGST and SGST sums and the number of lines. A group with a null rate holds
   * the lines of orders placed before GST was recorded.
   */
  @Query("select i.orderId, i.gstPercent, sum(i.taxablePaise), sum(i.cgstPaise), sum(i.sgstPaise), count(i) from OrderItemEntity i "
      + "where i.orderId in :ids group by i.orderId, i.gstPercent")
  List<Object[]> gstFor(@Param("ids") Collection<UUID> orderIds);
}
