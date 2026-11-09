package com.vijay.mall.pos.infra;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code shop_profile}, a single-row table. */
interface ShopProfileJpa extends JpaRepository<ShopProfileEntity, Integer> {}
