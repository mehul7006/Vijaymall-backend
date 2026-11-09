package com.vijay.mall.purchasing.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores the supplier master ({@code supplier}). The unique {@code nameKey} (trimmed, single spaces, lower case) is the identity of a supplier. */
@Repository
public class SupplierRepository {
  /** A supplier. */
  public record Supplier(UUID id, String name, String nameKey, String gstin, String stateCode, String phone, String address, int paymentTermsDays,
                         boolean active, Instant createdAt, Instant updatedAt) {}

  /** What the purchases say about a supplier. {@code totalDuePaise} is negative when the supplier owes the shop. */
  public record Stats(UUID supplierId, String lastInvoiceNo, Instant lastPurchaseAt, long purchaseCount, long totalDuePaise) {}

  private final JdbcClient jdbc;

  SupplierRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static Supplier supplier(ResultSet r, int row) throws SQLException {
    return new Supplier(r.getObject("id", UUID.class), r.getString("name"), r.getString("name_key"), r.getString("gstin"), r.getString("state_code"),
        r.getString("phone"), r.getString("address"), r.getInt("payment_terms_days"), r.getBoolean("active"), Sql.instant(r, "created_at"),
        Sql.instant(r, "updated_at"));
  }

  /** The key a name is compared by: trimmed, runs of white space as one space, lower case. */
  public static String key(String name) { return name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }

  /** Finds a supplier by id. */
  public Optional<Supplier> find(UUID id) { return jdbc.sql("select * from supplier where id = :id").param("id", id).query(SupplierRepository::supplier).optional(); }

  /** Finds a supplier by the key of its name. */
  public Optional<Supplier> findByKey(String nameKey) {
    return jdbc.sql("select * from supplier where name_key = :k").param("k", nameKey).query(SupplierRepository::supplier).optional();
  }

  /** Inserts the supplier unless one with the same name key exists; true when this call created it (safe when two requests race). */
  public boolean insertIfAbsent(Supplier s) {
    return jdbc.sql("""
        insert into supplier (id, name, name_key, gstin, state_code, phone, address, payment_terms_days, active, created_at, updated_at)
        values (:id, :name, :key, :gstin, :state, :phone, :address, :terms, :active, :at, :at) on conflict (name_key) do nothing""")
        .param("id", s.id()).param("name", s.name()).param("key", s.nameKey()).param("gstin", s.gstin()).param("state", s.stateCode())
        .param("phone", s.phone()).param("address", s.address()).param("terms", s.paymentTermsDays()).param("active", s.active())
        .param("at", Sql.ts(s.createdAt())).update() == 1;
  }

  /** Replaces the editable fields. A name that another supplier already uses fails with a duplicate-key error. */
  public void update(Supplier s, Instant now) {
    jdbc.sql("""
        update supplier set name = :name, name_key = :key, gstin = :gstin, state_code = :state, phone = :phone, address = :address,
          payment_terms_days = :terms, updated_at = :at where id = :id""")
        .param("id", s.id()).param("name", s.name()).param("key", s.nameKey()).param("gstin", s.gstin()).param("state", s.stateCode())
        .param("phone", s.phone()).param("address", s.address()).param("terms", s.paymentTermsDays()).param("at", Sql.ts(now)).update();
  }

  /** Turns a supplier on or off. */
  public void setActive(UUID id, boolean active, Instant now) {
    jdbc.sql("update supplier set active = :a, updated_at = :at where id = :id").param("a", active).param("at", Sql.ts(now)).param("id", id).update();
  }

  /** Fills the phone, GSTIN and state of a supplier that has none yet; values already stored are never overwritten. */
  public void fillMissing(UUID id, String phone, String gstin, String stateCode, Instant now) {
    jdbc.sql("""
        update supplier set phone = case when phone = '' then :phone else phone end, gstin = case when gstin = '' then :gstin else gstin end,
          state_code = case when state_code = '' then :state else state_code end, updated_at = :at
        where id = :id and ((phone = '' and :phone <> '') or (gstin = '' and :gstin <> '') or (state_code = '' and :state <> ''))""")
        .param("phone", phone).param("gstin", gstin).param("state", stateCode).param("at", Sql.ts(now)).param("id", id).update();
  }

  /** Suppliers by name, searchable by name, phone or GSTIN (contains, any case), with a bounded total. */
  public Page<Supplier> page(String q, Boolean active, int page, int size) {
    List<String> c = new ArrayList<>();
    Map<String, Object> params = new LinkedHashMap<>();
    if (q != null && !q.isBlank()) {
      c.add("(s.name_key like :q escape '\\' or s.phone like :q escape '\\' or lower(s.gstin) like :q escape '\\')");
      params.put("q", "%" + Sql.escapeLike(key(q)) + "%");
    }
    if (active != null) {
      c.add("s.active = :active");
      params.put("active", active);
    }
    String where = c.isEmpty() ? "" : " where " + String.join(" and ", c);
    var query = jdbc.sql("select s.* from supplier s" + where + " order by s.name_key, s.id limit " + (size + 1) + " offset " + (long) page * size);
    params.forEach(query::param);
    List<Supplier> fetched = query.query(SupplierRepository::supplier).list();
    return BoundedCount.assemble(fetched, page, size, () -> Sql.boundedCount(jdbc, "supplier s" + where, params));
  }

  /** Purchase figures for many suppliers in two queries. Suppliers without purchases get an empty entry. */
  public Map<UUID, Stats> stats(List<UUID> ids) {
    Map<UUID, Stats> out = new HashMap<>();
    ids.forEach(id -> out.put(id, new Stats(id, "", null, 0, 0)));
    if (ids.isEmpty()) return out;
    Map<UUID, String> lastInvoice = new HashMap<>();
    jdbc.sql("select distinct on (supplier_id) supplier_id, invoice_no from purchase where supplier_id in (:ids) order by supplier_id, created_at desc, id desc")
        .param("ids", ids).query((r, i) -> Map.entry(r.getObject("supplier_id", UUID.class), r.getString("invoice_no"))).list()
        .forEach(e -> lastInvoice.put(e.getKey(), e.getValue()));
    jdbc.sql("""
        select supplier_id, count(*) as cnt, max(created_at) as last_at, coalesce(sum(due_paise), 0) as due
        from purchase where supplier_id in (:ids) group by supplier_id""").param("ids", ids)
        .query((r, i) -> {
          UUID id = r.getObject("supplier_id", UUID.class);
          return new Stats(id, lastInvoice.getOrDefault(id, ""), Sql.instant(r, "last_at"), r.getLong("cnt"), r.getLong("due"));
        }).list().forEach(s -> out.put(s.supplierId(), s));
    return out;
  }
}
