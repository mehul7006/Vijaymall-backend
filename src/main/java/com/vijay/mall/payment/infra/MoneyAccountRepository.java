package com.vijay.mall.payment.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores money accounts and their hand-entered movements ({@code money_account}, {@code money_account_entry}). */
@Repository
public class MoneyAccountRepository {
  /**
   * A money account as stored.
   * @param kind BANK, GATEWAY, UPI, CARD_TERMINAL or CASH
   * @param provider free code such as razorpay, paytm or manual
   * @param identifier last four digits, a UPI id or a terminal id
   * @param settlesToAccountId the bank account a gateway, UPI or card terminal pays out into
   */
  public record Account(UUID id, String name, String kind, String provider, String identifier, UUID settlesToAccountId, boolean active,
                        String note, Instant createdAt, Instant updatedAt) {}

  /** A hand-entered movement on an account. {@code sourceType} is free for other modules (for example PURCHASE_PAYMENT). */
  @Schema(name = "MoneyAccountEntry")
  public record Entry(UUID id, UUID moneyAccountId, Instant at, String direction, long amountPaise, String sourceType, String sourceId,
                      String note, UUID createdBy) {}

  private static final String COLUMNS = "id, name, kind, provider, identifier, settles_to_account_id, active, note, created_at, updated_at";

  private final JdbcClient jdbc;

  MoneyAccountRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static Instant instant(ResultSet rs, String c) throws SQLException {
    Timestamp t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  private Account row(ResultSet rs, int i) throws SQLException {
    return new Account(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("kind"), rs.getString("provider"),
        rs.getString("identifier"), rs.getObject("settles_to_account_id", UUID.class), rs.getBoolean("active"), rs.getString("note"),
        instant(rs, "created_at"), instant(rs, "updated_at"));
  }

  /** One account. */
  public Optional<Account> find(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from money_account where id = :id").param("id", id).query(this::row).optional();
  }

  /** Accounts whose id is in the list, keyed by id. */
  public Map<UUID, Account> findAll(List<UUID> ids) {
    Map<UUID, Account> out = new HashMap<>();
    if (ids.isEmpty()) return out;
    jdbc.sql("select " + COLUMNS + " from money_account where id in (:ids)").param("ids", ids).query(this::row).list()
        .forEach(a -> out.put(a.id(), a));
    return out;
  }

  /** The first active account of this kind and provider (oldest first), if any. */
  public Optional<Account> firstActive(String kind, String provider) {
    return jdbc.sql("select " + COLUMNS + " from money_account where active and kind = :k and (cast(:p as text) is null or provider = :p) "
        + "order by created_at, id limit 1").param("k", kind).param("p", provider).query(this::row).optional();
  }

  /** The first active card machine or UPI account of a vendor, oldest first. */
  public Optional<Account> firstActiveCounter(String provider) {
    return jdbc.sql("select " + COLUMNS + " from money_account where active and provider = :p and kind in ('CARD_TERMINAL','UPI') order by created_at, id limit 1")
        .param("p", provider).query(this::row).optional();
  }

  /** The account of this kind, vendor and identifier (a card machine by its terminal id), if any. */
  public Optional<Account> findByIdentifier(String kind, String provider, String identifier) {
    return jdbc.sql("select " + COLUMNS + " from money_account where kind = :k and provider = :p and identifier = :i order by created_at, id limit 1")
        .param("k", kind).param("p", provider).param("i", identifier).query(this::row).optional();
  }

  /** Active accounts of these kinds, oldest first. */
  public List<Account> activeForKinds(List<String> kinds) {
    return jdbc.sql("select " + COLUMNS + " from money_account where active and kind in (:k) order by created_at, id limit 200").param("k", kinds)
        .query(this::row).list();
  }

  /** How many active accounts of this kind exist. */
  public long countActive(String kind) {
    return jdbc.sql("select count(*) from money_account where active and kind = :k").param("k", kind).query(Long.class).single();
  }

  /** Accounts, A to Z; every filter is optional ({@code q} matches name, provider or identifier). */
  public Page<Account> page(String kind, Boolean active, String q, int page, int size) {
    String where = "money_account where (cast(:k as text) is null or kind = :k) and (cast(:a as boolean) is null or active = :a) "
        + "and (cast(:q as text) is null or lower(name || ' ' || provider || ' ' || identifier) like :like)";
    String like = q == null ? null : "%" + q.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    List<Account> rows = jdbc.sql("select " + COLUMNS + " from " + where + " order by lower(name), id limit :n offset :o")
        .param("k", kind).param("a", active).param("q", q).param("like", like).param("n", size + 1).param("o", (long) page * size)
        .query(this::row).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql("select count(*) from (select 1 from " + where + " limit "
        + (BoundedCount.CAP + 1) + ") c").param("k", kind).param("a", active).param("q", q).param("like", like).query(Long.class).single());
  }

  /** True when another account already has this name (ignoring case). */
  public boolean nameTaken(String name, UUID exceptId) {
    return jdbc.sql("select exists(select 1 from money_account where lower(name) = lower(:n) and (cast(:x as uuid) is null or id <> :x))")
        .param("n", name).param("x", exceptId).query(Boolean.class).single();
  }

  /** Inserts a new, active account. */
  @Transactional
  public void insert(Account a) {
    jdbc.sql("insert into money_account (id, name, kind, provider, identifier, settles_to_account_id, active, note, created_at, updated_at) "
        + "values (:id, :name, :kind, :provider, :identifier, :settles, :active, :note, :now, :now)")
        .param("id", a.id()).param("name", a.name()).param("kind", a.kind()).param("provider", a.provider()).param("identifier", a.identifier())
        .param("settles", a.settlesToAccountId()).param("active", a.active()).param("note", a.note()).param("now", Timestamp.from(a.createdAt()))
        .update();
  }

  /** Rewrites the editable fields of an account. */
  @Transactional
  public void update(Account a, Instant now) {
    jdbc.sql("update money_account set name = :name, kind = :kind, provider = :provider, identifier = :identifier, "
        + "settles_to_account_id = :settles, note = :note, updated_at = :now where id = :id")
        .param("id", a.id()).param("name", a.name()).param("kind", a.kind()).param("provider", a.provider()).param("identifier", a.identifier())
        .param("settles", a.settlesToAccountId()).param("note", a.note()).param("now", Timestamp.from(now)).update();
  }

  /** Switches an account on or off; a card machine that owns the account follows it, so the two always agree. */
  @Transactional
  public void setActive(UUID id, boolean active, Instant now) {
    jdbc.sql("update money_account set active = :a, updated_at = :now where id = :id").param("a", active).param("now", Timestamp.from(now))
        .param("id", id).update();
    jdbc.sql("update payment_terminal set active = :a, updated_at = :now where money_account_id = :id and active <> :a").param("a", active)
        .param("now", Timestamp.from(now)).param("id", id).update();
  }

  /** Deletes an account; the caller has checked {@link #referenced}. */
  @Transactional
  public void delete(UUID id) { jdbc.sql("delete from money_account where id = :id").param("id", id).update(); }

  /** True when any payment, refund, counter line, settlement, entry, terminal payment or other account points at this one. */
  public boolean referenced(UUID id) {
    return jdbc.sql("select exists(select 1 from payment where money_account_id = :id) or exists(select 1 from refund where money_account_id = :id) "
        + "or exists(select 1 from pos_payment where money_account_id = :id) or exists(select 1 from pos_refund_payment where money_account_id = :id) "
        + "or exists(select 1 from settlement where money_account_id = :id) or exists(select 1 from money_account_entry where money_account_id = :id) "
        + "or exists(select 1 from card_terminal_session where money_account_id = :id) "
        + "or exists(select 1 from payment_terminal where money_account_id = :id) or exists(select 1 from purchase_payment where money_account_id = :id) "
        + "or exists(select 1 from supplier_refund where money_account_id = :id) or exists(select 1 from money_account where settles_to_account_id = :id)")
        .param("id", id).query(Boolean.class).single();
  }

  /** True when this account is an active destination other accounts settle into (a bank cannot be deactivated while they do). */
  public boolean hasActiveFeeders(UUID id) {
    return jdbc.sql("select exists(select 1 from money_account where settles_to_account_id = :id and active)").param("id", id)
        .query(Boolean.class).single();
  }

  /** Adds a hand-entered movement; false when the same source was already posted (a retry). */
  @Transactional
  public boolean insertEntry(Entry e, Instant now) {
    return jdbc.sql("insert into money_account_entry (id, money_account_id, at, direction, amount_paise, source_type, source_id, note, created_by, created_at) "
        + "values (:id, :acc, :at, :dir, :amt, :st, :sid, :note, :by, :now) on conflict do nothing")
        .param("id", e.id()).param("acc", e.moneyAccountId()).param("at", Timestamp.from(e.at())).param("dir", e.direction())
        .param("amt", e.amountPaise()).param("st", e.sourceType()).param("sid", e.sourceId()).param("note", e.note()).param("by", e.createdBy())
        .param("now", Timestamp.from(now)).update() == 1;
  }

  /** One entry. */
  public Optional<Entry> findEntry(UUID id) {
    return jdbc.sql("select id, money_account_id, at, direction, amount_paise, source_type, source_id, note, created_by from money_account_entry where id = :id")
        .param("id", id).query((rs, i) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("money_account_id", UUID.class), instant(rs, "at"),
            rs.getString("direction"), rs.getLong("amount_paise"), rs.getString("source_type"), rs.getString("source_id"), rs.getString("note"),
            rs.getObject("created_by", UUID.class))).optional();
  }
}
