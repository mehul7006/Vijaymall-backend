package com.vijay.mall.wallet.infra;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the single {@code coin_setting} row. */
interface SettingJpa extends JpaRepository<CoinSettingEntity, Integer> {}
