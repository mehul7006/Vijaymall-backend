package com.vijay.mall.pos.infra;

import org.springframework.data.jpa.repository.JpaRepository;

interface PosShopJpa extends JpaRepository<ShopSettingEntity, Integer> {}
