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

/** Spring Data access to the {@code orders} table. */
interface OrderJpa extends JpaRepository<OrderEntity, UUID> {
  Optional<OrderEntity> findByUserIdAndIdemKey(UUID userId, String idemKey);

  Page<OrderEntity> findByUserId(UUID userId, Pageable page);

  Page<OrderEntity> findByStatus(String status, Pageable page);

  /** pattern is already lower-case with % around it. Written out (not derived) so it uses the lower(order_no) search index. */
  @Query("select o from OrderEntity o where lower(o.orderNo) like :pattern")
  Page<OrderEntity> searchByNumber(@Param("pattern") String pattern, Pageable page);

  @Query("select o from OrderEntity o where o.status = :status and lower(o.orderNo) like :pattern")
  Page<OrderEntity> searchByStatusAndNumber(@Param("status") String status, @Param("pattern") String pattern, Pageable page);

  long countByUserIdAndStatusNot(UUID userId, String status);

  @Query("select count(o) from OrderEntity o where o.userId = :user and o.status not in ('DELIVERED', 'PICKED_UP', 'CANCELLED')")
  long countOpen(@Param("user") UUID user);

  @Query("select o from OrderEntity o where o.status = 'PAYMENT_PENDING' and o.expiresAt < :now order by o.expiresAt")
  List<OrderEntity> expiredPending(@Param("now") Instant now, Pageable page);

  @Query("select o.id, o.orderNo from OrderEntity o where o.id in :ids")
  List<Object[]> numbers(@Param("ids") Collection<UUID> ids);
}
