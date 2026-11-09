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

  /** One page of orders without counting them (the total comes from a cached count, see OrderRepository.adminList). */
  List<OrderEntity> findPageBy(Pageable page);

  /** One page of the orders of one status, without a count. */
  List<OrderEntity> findPageByStatus(String status, Pageable page);

  /** Number of orders of one status. */
  long countByStatus(String status);

  /** Same as {@link #searchByNumber} but one page without the count. */
  @Query("select o from OrderEntity o where lower(o.orderNo) like :pattern")
  List<OrderEntity> listByNumber(@Param("pattern") String pattern, Pageable page);

  /** How many orders have a number matching the pattern. */
  @Query("select count(o) from OrderEntity o where lower(o.orderNo) like :pattern")
  long countByNumber(@Param("pattern") String pattern);

  /** Same as {@link #searchByStatusAndNumber} but one page without the count. */
  @Query("select o from OrderEntity o where o.status = :status and lower(o.orderNo) like :pattern")
  List<OrderEntity> listByStatusAndNumber(@Param("status") String status, @Param("pattern") String pattern, Pageable page);

  /** How many orders of this status have a number matching the pattern. */
  @Query("select count(o) from OrderEntity o where o.status = :status and lower(o.orderNo) like :pattern")
  long countByStatusAndNumber(@Param("status") String status, @Param("pattern") String pattern);

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
