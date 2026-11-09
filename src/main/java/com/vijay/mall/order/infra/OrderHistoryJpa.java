package com.vijay.mall.order.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code order_status_history}. */
interface OrderHistoryJpa extends JpaRepository<OrderHistoryEntity, UUID> {
  List<OrderHistoryEntity> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}
