package com.vijay.mall.flash.infra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Plain SQL for the flash sale tables. The conditional UPDATEs are what make the quantity limits safe under concurrency. */
@Repository
public class FlashRepository {
  /**
   * A row of {@code flash_sale}: one sale with its schedule, channel and default discount; {@code cancelledAt} is null unless staff cancelled it.
   */
  public record Sale(UUID id, String name, String description, String bannerText, Instant startsAt, Instant endsAt, String channel,
                     String discountKind, long discountValue, int perCustomerLimit, Instant cancelledAt, UUID createdBy,
                     Instant createdAt, Instant updatedAt) {}

  /** discountKind null = the item follows the sale's default. maxQty null = unlimited. perCustomerLimit null = follows the sale. */
  public record Item(UUID id, UUID flashSaleId, UUID productId, String discountKind, long discountValue, Integer maxQty, int soldQty,
                     Integer perCustomerLimit) {}

  /** A sale together with its items. */
  public record SaleWithItems(Sale sale, List<Item> items) {}

  /** A product that is already in another sale whose time window overlaps. */
  public record Clash(UUID saleId, String saleName, UUID productId) {}

  /** The item row after its sold_qty was raised: enough to check the customer limit and raise the sold-out alert. */
  public record Claimed(UUID itemId, Integer maxQty, int soldQty, Integer itemLimit, int saleLimit, String saleName) {}

  /** A consumption row that was given back, with the units to return to the item. */
  public record Released(UUID itemId, UUID saleId, UUID productId, int qty, String channel) {}

  /** A sale claimed by the lifecycle job for announcement. */
  public record Lifecycle(UUID saleId, String name, Instant startsAt, Instant endsAt, boolean startWasNotified) {}

  /**
   * Sales figures of one or more sales: units, distinct online orders and counter bills, revenue and discount in paise, distinct customers.
   */
  public record Totals(long units, long orders, long bills, long revenuePaise, long discountPaise, long customers) {}

  /** Per-item totals with the item's cap and sold count. */
  public record ItemTotals(UUID productId, long units, long revenuePaise, long discountPaise, Integer maxQty, int soldQty) {}

  /** Totals for one channel. */
  public record ChannelTotals(String channel, long units, long revenuePaise, long discountPaise) {}

  /** Totals for one shop-time hour. */
  public record HourTotals(String hour, long units, long revenuePaise) {}

  /** A product's totals across several sales, for the best-sellers list. */
  public record BestItem(UUID productId, long units, long revenuePaise, long discountPaise) {}

  /** How many sales are scheduled and how many are running. */
  public record StateCounts(long scheduled, long active) {}

  private final JdbcClient jdbc;

  FlashRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static OffsetDateTime utc(Instant t) { return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC); }

  private static Instant at(ResultSet rs, String col) throws SQLException {
    OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
    int v = rs.getInt(col);
    return rs.wasNull() ? null : v;
  }

  private static final RowMapper<Sale> SALE = (rs, _) -> new Sale(rs.getObject("id", UUID.class), rs.getString("name"),
      rs.getString("description"), rs.getString("banner_text"), at(rs, "starts_at"), at(rs, "ends_at"), rs.getString("channel"),
      rs.getString("discount_kind"), rs.getLong("discount_value"), rs.getInt("per_customer_limit"), at(rs, "cancelled_at"),
      rs.getObject("created_by", UUID.class), at(rs, "created_at"), at(rs, "updated_at"));

  private static final RowMapper<Item> ITEM = (rs, _) -> new Item(rs.getObject("id", UUID.class), rs.getObject("flash_sale_id", UUID.class),
      rs.getObject("product_id", UUID.class), rs.getString("discount_kind"), rs.getLong("discount_value"), intOrNull(rs, "max_qty"),
      rs.getInt("sold_qty"), intOrNull(rs, "per_customer_limit"));

  private static final RowMapper<Totals> TOTALS = (rs, _) -> new Totals(rs.getLong("units"), rs.getLong("orders"), rs.getLong("bills"),
      rs.getLong("revenue"), rs.getLong("discount"), rs.getLong("customers"));

  private static final String TOTAL_COLUMNS = "coalesce(sum(c.qty), 0) units, count(distinct c.order_id) orders, "
      + "count(distinct c.pos_sale_id) bills, coalesce(sum(c.unit_price_paise * c.qty), 0) revenue, "
      + "coalesce(sum(c.discount_paise), 0) discount, count(distinct c.user_id) customers";

  // ---------- locks ----------
  /** Serialises creating or moving sales, so two staff members cannot both slip the same product into overlapping sales. */
  @Transactional
  public void lockSchedule() { jdbc.sql("select pg_advisory_xact_lock(7742001)").query().singleRow(); }

  // ---------- sales ----------
  /** Inserts a new sale row. */
  @Transactional
  public void insertSale(Sale s) {
    jdbc.sql("insert into flash_sale (id, name, description, banner_text, starts_at, ends_at, channel, discount_kind, discount_value, "
        + "per_customer_limit, created_by, created_at, updated_at) values (:id, :name, :description, :banner, :starts, :ends, :channel, "
        + ":kind, :value, :limit, :by, :created, :created)")
        .param("id", s.id()).param("name", s.name()).param("description", s.description()).param("banner", s.bannerText())
        .param("starts", utc(s.startsAt())).param("ends", utc(s.endsAt())).param("channel", s.channel()).param("kind", s.discountKind())
        .param("value", s.discountValue()).param("limit", s.perCustomerLimit()).param("by", s.createdBy())
        .param("created", utc(s.createdAt())).update();
  }

  /** Overwrites all editable fields of a sale, including {@code cancelledAt}. */
  @Transactional
  public void updateSale(Sale s) {
    jdbc.sql("update flash_sale set name = :name, description = :description, banner_text = :banner, starts_at = :starts, "
        + "ends_at = :ends, channel = :channel, discount_kind = :kind, discount_value = :value, per_customer_limit = :limit, "
        + "cancelled_at = :cancelled, updated_at = :updated where id = :id")
        .param("id", s.id()).param("name", s.name()).param("description", s.description()).param("banner", s.bannerText())
        .param("starts", utc(s.startsAt())).param("ends", utc(s.endsAt())).param("channel", s.channel()).param("kind", s.discountKind())
        .param("value", s.discountValue()).param("limit", s.perCustomerLimit()).param("cancelled", utc(s.cancelledAt()))
        .param("updated", utc(s.updatedAt())).update();
  }

  /** Finds a sale by id. */
  public Optional<Sale> findSale(UUID id) {
    return jdbc.sql("select * from flash_sale where id = :id").param("id", id).query(SALE).optional();
  }

  /** Sales in the given state (null = all), newest start first. */
  public List<Sale> list(String status, Instant now, int limit, int offset) {
    return jdbc.sql("select * from flash_sale where " + statusClause(status) + " order by starts_at desc, id limit :limit offset :offset")
        .param("now", utc(now)).param("limit", limit).param("offset", offset).query(SALE).list();
  }

  /** Number of sales in the given state (null = all), for paging. */
  public long count(String status, Instant now) {
    return jdbc.sql("select count(*) from flash_sale where " + statusClause(status)).param("now", utc(now)).query(Long.class).single();
  }

  private static String statusClause(String status) {
    if (status == null) return "(cast(:now as timestamptz) is not null)";
    return switch (status) {
      case "SCHEDULED" -> "cancelled_at is null and starts_at > :now";
      case "ACTIVE" -> "cancelled_at is null and starts_at <= :now and ends_at > :now";
      case "ENDED" -> "cancelled_at is null and ends_at <= :now";
      case "CANCELLED" -> "cancelled_at is not null and (cast(:now as timestamptz) is not null)";
      default -> throw new IllegalArgumentException("unknown status " + status);
    };
  }

  /** Sales that start inside [from, to). */
  public List<Sale> startingBetween(Instant from, Instant to) {
    return jdbc.sql("select * from flash_sale where starts_at >= :from and starts_at < :to order by starts_at, id")
        .param("from", utc(from)).param("to", utc(to)).query(SALE).list();
  }

  /** Counts the scheduled and running sales (cancelled and ended ones are ignored). */
  public StateCounts stateCounts(Instant now) {
    return jdbc.sql("select count(*) filter (where starts_at > :now) s, count(*) filter (where starts_at <= :now and ends_at > :now) a "
        + "from flash_sale where cancelled_at is null and ends_at > :now").param("now", utc(now))
        .query((rs, _) -> new StateCounts(rs.getLong("s"), rs.getLong("a"))).single();
  }

  // ---------- items ----------
  /** The items of a sale in the order they were added. */
  public List<Item> items(UUID saleId) {
    return jdbc.sql("select * from flash_sale_item where flash_sale_id = :s order by created_at, id").param("s", saleId).query(ITEM).list();
  }

  /** Finds an item by id. */
  public Optional<Item> findItem(UUID itemId) {
    return jdbc.sql("select * from flash_sale_item where id = :id").param("id", itemId).query(ITEM).optional();
  }

  /** Inserts a new item row. */
  @Transactional
  public void insertItem(Item i, Instant now) {
    jdbc.sql("insert into flash_sale_item (id, flash_sale_id, product_id, discount_kind, discount_value, max_qty, sold_qty, "
        + "per_customer_limit, created_at) values (:id, :sale, :product, :kind, :value, :max, :sold, :limit, :now)")
        .param("id", i.id()).param("sale", i.flashSaleId()).param("product", i.productId()).param("kind", i.discountKind())
        .param("value", i.discountValue()).param("max", i.maxQty()).param("sold", i.soldQty()).param("limit", i.perCustomerLimit())
        .param("now", utc(now)).update();
  }

  /** Updates an item's discount, cap and customer limit; the sold count is never touched here. */
  @Transactional
  public void updateItem(Item i) {
    jdbc.sql("update flash_sale_item set discount_kind = :kind, discount_value = :value, max_qty = :max, per_customer_limit = :limit "
        + "where id = :id").param("id", i.id()).param("kind", i.discountKind()).param("value", i.discountValue())
        .param("max", i.maxQty()).param("limit", i.perCustomerLimit()).update();
  }

  /** Deletes an item row. */
  @Transactional
  public void deleteItem(UUID itemId) { jdbc.sql("delete from flash_sale_item where id = :id").param("id", itemId).update(); }

  /** Deletes a sale row; its items go with it (cascade). */
  @Transactional
  public void deleteSale(UUID id) { jdbc.sql("delete from flash_sale where id = :id").param("id", id).update(); }

  /** True when any unit of any item of the sale was ever counted, given back or not. */
  public boolean saleHasHistory(UUID saleId) {
    return jdbc.sql("select exists (select 1 from flash_consumption where flash_sale_id = :id)").param("id", saleId)
        .query(Boolean.class).single();
  }

  /** True when any units of the item were ever counted, given back or not. */
  public boolean hasHistory(UUID itemId) {
    return jdbc.sql("select exists (select 1 from flash_consumption where flash_item_id = :id)").param("id", itemId)
        .query(Boolean.class).single();
  }

  /** Every sale that is not cancelled and has not ended, with its items (the pricing code caches this). */
  public List<SaleWithItems> openSales(Instant now) {
    List<Sale> sales = jdbc.sql("select * from flash_sale where cancelled_at is null and ends_at > :now order by starts_at, id")
        .param("now", utc(now)).query(SALE).list();
    if (sales.isEmpty()) return List.of();
    Map<UUID, List<Item>> items = new LinkedHashMap<>();
    jdbc.sql("select i.* from flash_sale_item i join flash_sale s on s.id = i.flash_sale_id where s.cancelled_at is null and s.ends_at > :now "
        + "order by i.created_at, i.id").param("now", utc(now)).query(ITEM).list()
        .forEach(i -> items.computeIfAbsent(i.flashSaleId(), _ -> new ArrayList<>()).add(i));
    return sales.stream().map(s -> new SaleWithItems(s, items.getOrDefault(s.id(), List.of()))).toList();
  }

  /** Products of other (not cancelled) sales whose window overlaps [start, end). */
  public List<Clash> clashes(Collection<UUID> productIds, Instant start, Instant end, UUID exceptSale) {
    if (productIds.isEmpty()) return List.of();
    return jdbc.sql("select s.id, s.name, i.product_id from flash_sale s join flash_sale_item i on i.flash_sale_id = s.id "
        + "where s.cancelled_at is null and i.product_id in (:ids) and s.starts_at < :end and s.ends_at > :start and s.id <> :except "
        + "order by s.starts_at, s.id").param("ids", productIds).param("start", utc(start)).param("end", utc(end))
        .param("except", exceptSale).query((rs, _) -> new Clash(rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getObject("product_id", UUID.class))).list();
  }

  // ---------- quantity counting ----------
  /** Units sold so far, for the given items (fresh from the table: never cached). */
  public Map<UUID, Integer> soldQtys(Collection<UUID> itemIds) {
    if (itemIds.isEmpty()) return Map.of();
    Map<UUID, Integer> out = new LinkedHashMap<>();
    jdbc.sql("select id, sold_qty from flash_sale_item where id in (:ids)").param("ids", itemIds)
        .query((rs, _) -> out.put(rs.getObject("id", UUID.class), rs.getInt("sold_qty"))).list();
    return out;
  }

  /** Units this customer holds in the sale item (given-back units not counted). */
  public int userQty(UUID itemId, UUID userId) {
    return jdbc.sql("select coalesce(sum(qty), 0) from flash_consumption where flash_item_id = :i and user_id = :u and released_at is null")
        .param("i", itemId).param("u", userId).query(Integer.class).single();
  }

  /**
   * Atomically takes qty from the item, but only while the sale is on (started, not ended, not cancelled), is open on the channel,
   * and the item's cap still has room. One UPDATE, so two buyers can never both get the last unit.
   */
  @Transactional
  public Optional<Claimed> claim(UUID saleId, UUID productId, int qty, List<String> channels, Instant now) {
    return jdbc.sql("update flash_sale_item i set sold_qty = i.sold_qty + :q from flash_sale s where i.flash_sale_id = :sale "
        + "and i.product_id = :product and s.id = i.flash_sale_id and s.cancelled_at is null and s.starts_at <= :now and s.ends_at > :now "
        + "and s.channel in (:channels) and (i.max_qty is null or i.sold_qty + :q <= i.max_qty) "
        + "returning i.id, i.max_qty, i.sold_qty, i.per_customer_limit, s.per_customer_limit as sale_limit, s.name as sale_name")
        .param("q", qty).param("sale", saleId).param("product", productId).param("now", utc(now)).param("channels", channels)
        .query((rs, _) -> new Claimed(rs.getObject("id", UUID.class), intOrNull(rs, "max_qty"), rs.getInt("sold_qty"),
            intOrNull(rs, "per_customer_limit"), rs.getInt("sale_limit"), rs.getString("sale_name"))).optional();
  }

  /** Why a claim was refused: ENDED when the sale is gone / not on for this channel, else the cap. */
  public String refusalReason(UUID saleId, List<String> channels, Instant now) {
    boolean on = jdbc.sql("select exists (select 1 from flash_sale where id = :id and cancelled_at is null and starts_at <= :now "
        + "and ends_at > :now and channel in (:channels))").param("id", saleId).param("now", utc(now)).param("channels", channels)
        .query(Boolean.class).single();
    return on ? "LIMIT_REACHED" : "ENDED";
  }

  /** Takes {@code qty} back off the item's sold count. */
  @Transactional
  public void unclaim(UUID itemId, int qty) {
    jdbc.sql("update flash_sale_item set sold_qty = sold_qty - :q where id = :id").param("q", qty).param("id", itemId).update();
  }

  /**
   * Records that units were sold at a flash price, for either an online order ({@code orderId}) or a counter bill ({@code posSaleId}); the discount is worked out from the two prices.
   */
  @Transactional
  public void insertConsumption(UUID itemId, UUID saleId, UUID productId, String channel, UUID orderId, UUID posSaleId, UUID userId,
                                int qty, long unitPricePaise, long listPricePaise, Instant now) {
    jdbc.sql("insert into flash_consumption (id, flash_item_id, flash_sale_id, product_id, channel, order_id, pos_sale_id, user_id, qty, "
        + "unit_price_paise, list_price_paise, discount_paise, created_at) values (:id, :item, :sale, :product, :channel, :order, :pos, "
        + ":user, :qty, :unit, :list, :discount, :now)").param("id", UUID.randomUUID()).param("item", itemId).param("sale", saleId)
        .param("product", productId).param("channel", channel).param("order", orderId).param("pos", posSaleId).param("user", userId)
        .param("qty", qty).param("unit", unitPricePaise).param("list", listPricePaise)
        .param("discount", (listPricePaise - unitPricePaise) * qty).param("now", utc(now)).update();
  }

  /** True the first time an item is found sold out (so staff are told once). */
  @Transactional
  public boolean markSoldOutNotified(UUID itemId, Instant now) {
    return jdbc.sql("update flash_sale_item set sold_out_notified_at = :now where id = :id and sold_out_notified_at is null")
        .param("id", itemId).param("now", utc(now)).update() > 0;
  }

  /** Marks the order's (or bill's) lines given back and returns them; a second call finds nothing. */
  @Transactional
  public List<Released> release(UUID refId, Instant now) {
    List<Released> rows = jdbc.sql("update flash_consumption set released_at = :now where (order_id = :ref or pos_sale_id = :ref) "
        + "and released_at is null returning flash_item_id, flash_sale_id, product_id, qty, channel").param("ref", refId)
        .param("now", utc(now)).query((rs, _) -> new Released(rs.getObject("flash_item_id", UUID.class),
            rs.getObject("flash_sale_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getInt("qty"),
            rs.getString("channel"))).list();
    rows.forEach(r -> unclaim(r.itemId(), r.qty()));
    return rows;
  }

  // ---------- lifecycle ----------
  /** Takes (once) the sales that are on and not yet announced. */
  @Transactional
  public List<Lifecycle> claimStarted(Instant now) {
    return jdbc.sql("update flash_sale set started_notified_at = :now where started_notified_at is null and cancelled_at is null "
        + "and starts_at <= :now and ends_at > :now returning id, name, starts_at, ends_at").param("now", utc(now))
        .query((rs, _) -> new Lifecycle(rs.getObject("id", UUID.class), rs.getString("name"), at(rs, "starts_at"), at(rs, "ends_at"),
            false)).list();
  }

  /** Takes (once) the sales that ended and were not yet announced. startWasNotified is false when nobody heard it started. */
  @Transactional
  public List<Lifecycle> claimEnded(Instant now) {
    return jdbc.sql("update flash_sale s set ended_notified_at = :now, started_notified_at = coalesce(s.started_notified_at, :now) "
        + "from (select id, started_notified_at as was from flash_sale where ended_notified_at is null and cancelled_at is null "
        + "and ends_at <= :now for update) old where s.id = old.id returning s.id, s.name, s.starts_at, s.ends_at, old.was")
        .param("now", utc(now)).query((rs, _) -> new Lifecycle(rs.getObject("id", UUID.class), rs.getString("name"),
            at(rs, "starts_at"), at(rs, "ends_at"), rs.getObject("was", OffsetDateTime.class) != null)).list();
  }

  // ---------- reports ----------
  /** Totals of one sale, ignoring units given back. */
  public Totals totals(UUID saleId) {
    return jdbc.sql("select " + TOTAL_COLUMNS + " from flash_consumption c where c.flash_sale_id = :s and c.released_at is null")
        .param("s", saleId).query(TOTALS).single();
  }

  /** Per-item totals of one sale, best seller first; items with no sales are included with zeros. */
  public List<ItemTotals> itemTotals(UUID saleId) {
    return jdbc.sql("select i.product_id, i.max_qty, i.sold_qty, coalesce(sum(c.qty), 0) units, coalesce(sum(c.unit_price_paise * c.qty), 0) revenue, "
        + "coalesce(sum(c.discount_paise), 0) discount from flash_sale_item i left join flash_consumption c on c.flash_item_id = i.id "
        + "and c.released_at is null where i.flash_sale_id = :s group by i.id order by units desc, i.created_at, i.id").param("s", saleId)
        .query((rs, _) -> new ItemTotals(rs.getObject("product_id", UUID.class), rs.getLong("units"), rs.getLong("revenue"),
            rs.getLong("discount"), intOrNull(rs, "max_qty"), rs.getInt("sold_qty"))).list();
  }

  /** Totals of one sale per channel. */
  public List<ChannelTotals> channelTotals(UUID saleId) {
    return jdbc.sql("select c.channel, sum(c.qty) units, sum(c.unit_price_paise * c.qty) revenue, sum(c.discount_paise) discount "
        + "from flash_consumption c where c.flash_sale_id = :s and c.released_at is null group by c.channel order by c.channel")
        .param("s", saleId).query((rs, _) -> new ChannelTotals(rs.getString("channel"), rs.getLong("units"), rs.getLong("revenue"),
            rs.getLong("discount"))).list();
  }

  /** Units and revenue per shop-time hour ("2026-11-08 14:00"). */
  public List<HourTotals> hourTotals(UUID saleId) {
    return jdbc.sql("select to_char(date_trunc('hour', c.created_at at time zone 'Asia/Kolkata'), 'YYYY-MM-DD HH24:MI') hr, "
        + "sum(c.qty) units, sum(c.unit_price_paise * c.qty) revenue from flash_consumption c where c.flash_sale_id = :s "
        + "and c.released_at is null group by 1 order by 1").param("s", saleId)
        .query((rs, _) -> new HourTotals(rs.getString("hr"), rs.getLong("units"), rs.getLong("revenue"))).list();
  }

  /** Totals per sale for these sales; sales with no consumption are absent. */
  public Map<UUID, Totals> totalsBySale(Collection<UUID> saleIds) {
    if (saleIds.isEmpty()) return Map.of();
    Map<UUID, Totals> out = new LinkedHashMap<>();
    jdbc.sql("select c.flash_sale_id sale, " + TOTAL_COLUMNS + " from flash_consumption c where c.flash_sale_id in (:ids) "
        + "and c.released_at is null group by c.flash_sale_id").param("ids", saleIds)
        .query((rs, _) -> out.put(rs.getObject("sale", UUID.class), TOTALS.mapRow(rs, 0))).list();
    return out;
  }

  /** The best-selling products across these sales, by units then revenue, at most {@code limit}. */
  public List<BestItem> bestItems(Collection<UUID> saleIds, int limit) {
    if (saleIds.isEmpty()) return List.of();
    return jdbc.sql("select c.product_id, sum(c.qty) units, sum(c.unit_price_paise * c.qty) revenue, sum(c.discount_paise) discount "
        + "from flash_consumption c where c.flash_sale_id in (:ids) and c.released_at is null group by c.product_id "
        + "order by units desc, revenue desc, c.product_id limit :limit").param("ids", saleIds).param("limit", limit)
        .query((rs, _) -> new BestItem(rs.getObject("product_id", UUID.class), rs.getLong("units"), rs.getLong("revenue"),
            rs.getLong("discount"))).list();
  }

  /** Distinct customers across several sales (a customer who bought in two sales counts once). */
  public long customersAcross(Collection<UUID> saleIds) {
    if (saleIds.isEmpty()) return 0;
    return jdbc.sql("select count(distinct c.user_id) from flash_consumption c where c.flash_sale_id in (:ids) and c.released_at is null")
        .param("ids", saleIds).query(Long.class).single();
  }
}
