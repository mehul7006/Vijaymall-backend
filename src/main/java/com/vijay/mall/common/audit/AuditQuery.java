package com.vijay.mall.common.audit;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the audit trail: who did what, when, filtered by any combination of person, action, record and time. */
@Repository
public class AuditQuery {
  /**
   * One audit trail entry.
   * @param actorId the person who acted; {@code null} for the system
   * @param action what was done, for example {@code ORDER_STATUS}
   * @param entity the kind of record, for example {@code orders}
   * @param entityId the record's id as text
   * @param change short description of the change
   * @param at when it happened
   */
  public record Entry(UUID id, UUID actorId, String action, String entity, String entityId, String change, Instant at) {}

  @PersistenceContext
  private EntityManager em;

  /**
   * Newest first. A null filter is not applied. {@code from} is included, {@code to} is not. The audit trail is the biggest table
   * in the system, so the total is exact but counted only up to {@link BoundedCount#CAP} rows (see {@link Page}).
   *
   * @return one page; {@code total} is exact up to the cap, {@code hasMore} is always exact
   */
  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public Page<Entry> search(UUID actor, String action, String entity, String entityId, Instant from, Instant to, int page, int size) {
    StringBuilder where = new StringBuilder(" where 1 = 1");
    StringBuilder sql = new StringBuilder("audit_log a where 1 = 1");
    Map<String, Object> params = new HashMap<>();
    if (actor != null) { where.append(" and a.actorId = :actor"); sql.append(" and a.actor_id = :actor"); params.put("actor", actor); }
    if (action != null) { where.append(" and a.action = :action"); sql.append(" and a.action = :action"); params.put("action", action); }
    if (entity != null) { where.append(" and a.entity = :entity"); sql.append(" and a.entity = :entity"); params.put("entity", entity); }
    if (entityId != null) { where.append(" and a.entityId = :entityId"); sql.append(" and a.entity_id = :entityId"); params.put("entityId", entityId); }
    if (from != null) { where.append(" and a.createdAt >= :from"); sql.append(" and a.created_at >= :from"); params.put("from", from); }
    if (to != null) { where.append(" and a.createdAt < :to"); sql.append(" and a.created_at < :to"); params.put("to", to); }

    Query rows = em.createQuery("select a from AuditLogEntity a" + where + " order by a.createdAt desc, a.id");
    params.forEach(rows::setParameter);
    rows.setFirstResult(page * size).setMaxResults(size + 1); // one extra row tells whether another page follows
    List<Entry> items = new ArrayList<>();
    for (AuditLogEntity a : (List<AuditLogEntity>) rows.getResultList()) {
      items.add(new Entry(a.id, a.actorId, a.action, a.entity, a.entityId, a.detail, a.createdAt));
    }
    return BoundedCount.assemble(items, page, size, () -> BoundedCount.count(em, sql.toString(), params));
  }
}
