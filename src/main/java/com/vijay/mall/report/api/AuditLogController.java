package com.vijay.mall.report.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.report.app.AuditLogService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin only (everything under /admin): the record of who changed what. */
@RestController
@RequestMapping("/api/v1/admin")
public class AuditLogController {
  private final AuditLogService audit;

  /** Creates the controller. */
  public AuditLogController(AuditLogService audit) { this.audit = audit; }

  /** Any mix of filters; newest first. entity is the kind of record (orders, pos_sale, coupon...), entityId its id. */
  @GetMapping("/audit-log")
  public Page<AuditLogService.Row> search(@RequestParam(required = false) UUID actorId, @RequestParam(required = false) String action,
                                          @RequestParam(required = false) String entity, @RequestParam(required = false) String entityId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return audit.search(actorId, action, entity, entityId, from, to, page, size);
  }
}
