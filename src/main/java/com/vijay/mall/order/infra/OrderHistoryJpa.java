package com.vijay.mall.order.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderHistoryJpa extends JpaRepository<OrderHistoryEntity, UUID> {
  List<OrderHistoryEntity> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}
