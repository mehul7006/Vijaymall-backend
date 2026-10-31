package com.vijay.mall.wallet.infra;

import org.springframework.data.jpa.repository.JpaRepository;

interface SettingJpa extends JpaRepository<CoinSettingEntity, Integer> {}
