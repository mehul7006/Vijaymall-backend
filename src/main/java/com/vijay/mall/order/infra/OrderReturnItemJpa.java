package com.vijay.mall.order.infra;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code order_return_item}. */
interface OrderReturnItemJpa extends JpaRepository<OrderReturnItemEntity, UUID> {
  List<OrderReturnItemEntity> findByReturnIdIn(Collection<UUID> returnIds);
}
