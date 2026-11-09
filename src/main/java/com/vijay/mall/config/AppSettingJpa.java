package com.vijay.mall.config;

import org.springframework.data.jpa.repository.JpaRepository;

interface AppSettingJpa extends JpaRepository<SettingEntity, String> {}
