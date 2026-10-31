package com.vijay.mall.catalog.infra;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductJpa extends JpaRepository<ProductEntity, UUID> {}
