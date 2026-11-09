package com.vijay.mall.pos.infra;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code shop_setting}, the single-row table of shop settings. */
interface PosShopJpa extends JpaRepository<ShopSettingEntity, Integer> {}
