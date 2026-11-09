package com.vijay.mall.common.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code audit_log}. */
interface AuditLogJpa extends JpaRepository<AuditLogEntity, UUID> {}
