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

  /** One page of orders without counting them (the total comes from a cached count, see OrderRepository.adminList). */
  List<OrderEntity> findPageBy(Pageable page);

  /** One page of the orders of one status, without a count. */
  List<OrderEntity> findPageByStatus(String status, Pageable page);


  /**
   * One page, without a count, of the orders whose number contains the text or that belong to a customer whose name, e-mail or
   * mobile number contains it. Every matching customer counts (no cap): the customer match is a sub-select the database evaluates
   * with its trigram indexes. The two halves are a UNION so each uses its own index; one OR would scan every order.
   * {@code pattern} is lower-case with {@code %} around it.
   */
  @Query("select o from OrderEntity o where o.id in (select a.id from OrderEntity a where lower(a.orderNo) like :pattern "
      + "union select b.id from OrderEntity b where b.userId in (select u.id from UserEntity u where lower(coalesce(u.name, '')) like :pattern "
      + "or lower(coalesce(u.email, '')) like :pattern or coalesce(u.mobile, '') like :pattern))")
  List<OrderEntity> listByNumberOrCustomer(@Param("pattern") String pattern, Pageable page);

  /** Same as {@link #listByNumberOrCustomer} for one status. */
  @Query("select o from OrderEntity o where o.status = :status and o.id in (select a.id from OrderEntity a where lower(a.orderNo) like :pattern "
      + "union select b.id from OrderEntity b where b.userId in (select u.id from UserEntity u where lower(coalesce(u.name, '')) like :pattern "
      + "or lower(coalesce(u.email, '')) like :pattern or coalesce(u.mobile, '') like :pattern))")
  List<OrderEntity> listByStatusAndNumberOrCustomer(@Param("status") String status, @Param("pattern") String pattern, Pageable page);

  long countByUserIdAndStatusNot(UUID userId, String status);

  @Query("select count(o) from OrderEntity o where o.userId = :user and o.status not in ('DELIVERED', 'PICKED_UP', 'CANCELLED')")
  long countOpen(@Param("user") UUID user);

  @Query("select o from OrderEntity o where o.status = 'PAYMENT_PENDING' and o.expiresAt < :now order by o.expiresAt")
  List<OrderEntity> expiredPending(@Param("now") Instant now, Pageable page);

  @Query("select o.id, o.orderNo from OrderEntity o where o.id in :ids")
  List<Object[]> numbers(@Param("ids") Collection<UUID> ids);

  /** The order number of the order each of these returns belongs to (return id, order number). */
  @Query("select r.id, o.orderNo from OrderReturnEntity r join OrderEntity o on o.id = r.orderId where r.id in :ids")
  List<Object[]> returnNumbers(@Param("ids") Collection<UUID> ids);
}
