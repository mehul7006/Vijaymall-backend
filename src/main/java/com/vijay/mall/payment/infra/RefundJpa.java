package com.vijay.mall.payment.infra;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RefundJpa extends JpaRepository<RefundEntity, UUID> {}
