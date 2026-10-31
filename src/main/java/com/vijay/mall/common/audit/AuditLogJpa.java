package com.vijay.mall.common.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditLogJpa extends JpaRepository<AuditLogEntity, UUID> {}
