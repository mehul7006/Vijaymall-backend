package com.vijay.mall.config;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the {@code app_setting} table. */
interface AppSettingJpa extends JpaRepository<SettingEntity, String> {}
