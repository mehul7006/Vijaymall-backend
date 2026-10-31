package com.vijay.mall.notification.infra;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationJpa extends JpaRepository<NotificationEntity, UUID> {}
