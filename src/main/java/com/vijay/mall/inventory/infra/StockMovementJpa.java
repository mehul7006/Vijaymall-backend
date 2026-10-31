package com.vijay.mall.inventory.infra;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface StockMovementJpa extends JpaRepository<StockMovementEntity, UUID> {}
