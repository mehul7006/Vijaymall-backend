package com.vijay.mall.report.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditQuery;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The audit trail for the admin: every recorded change with the name of the person who made it. */
@Service
public class AuditLogService {
  /** actorName is the person's name or e-mail, null when the action was the system's own. */
  public record Row(UUID id, UUID actorId, String actorName, String action, String entity, String entityId, String change, Instant at) {}

  private static final long MAX_DAYS = 93;

  private final AuditQuery audit;
  private final UserRepository users;

  public AuditLogService(AuditQuery audit, UserRepository users) {
    this.audit = audit;
    this.users = users;
  }

  /** from and to are shop-calendar days, both included. */
  @Transactional(readOnly = true)
  public Page<Row> search(UUID actor, String action, String entity, String entityId, LocalDate from, LocalDate to, int page, int size) {
    if (from != null && to != null && to.isBefore(from)) throw ApiException.validation("to", "must not be before from");
    if (from != null && to != null && java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
      throw ApiException.validation("to", "range is limited to " + MAX_DAYS + " days");
    }
    Instant f = from == null ? null : from.atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant();
    Instant t = to == null ? null : to.plusDays(1).atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant();
    Page<AuditQuery.Entry> p = audit.search(actor, blank(action), blank(entity), blank(entityId), f, t, Math.max(0, page), Page.clampSize(size));
    Set<UUID> ids = p.items().stream().map(AuditQuery.Entry::actorId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    Map<UUID, User> people = users.findAllByIds(ids).stream().collect(Collectors.toMap(User::id, Function.identity()));
    List<Row> rows = p.items().stream().map(e -> {
      User u = e.actorId() == null ? null : people.get(e.actorId());
      String name = u == null ? null : u.name() != null && !u.name().isBlank() ? u.name() : u.email();
      return new Row(e.id(), e.actorId(), name, e.action(), e.entity(), e.entityId(), e.change(), e.at());
    }).toList();
    return new Page<>(rows, p.page(), p.size(), p.total());
  }

  private static String blank(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
