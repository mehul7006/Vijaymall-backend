package com.vijay.mall.inventory.infra;

import com.vijay.mall.inventory.domain.Lot;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Stock lots and the record of which lot each unit came from ({@code stock_lot}, {@code stock_allocation}). Every method that changes a
 * lot runs inside the caller's transaction, next to the change of the product's stock row, so the two always agree.
 */
@Repository
public class LotRepository {
  /** A product's lots that still have stock, locked, with the flags and current price that decide how they are used. */
  public record ProductLots(UUID productId, boolean lotPriced, boolean trackExpiry, long pricePaise, long mrpPaise, List<Lot> lots) {
    /** The lots in the order they are used: earliest expiry first for dated goods, else oldest first. */
    public List<Lot> inUseOrder() {
      List<Lot> sorted = new ArrayList<>(lots);
      sorted.sort(trackExpiry ? Lot.FEFO : Lot.FIFO);
      return sorted;
    }
  }

  /** One recorded take of units from a lot. */
  public record Allocation(UUID id, UUID lotId, UUID productId, String refType, UUID refId, int qty, BigDecimal unitCost, long costPaise,
                           int returnedQty, long returnedCostPaise) {}

  /** Units put back into a lot, with the allocation they come from (null when there is none) and the cost taken back. */
  public record Back(UUID lotId, int qty, UUID allocationId, long costPaise) {}

  private final JdbcClient jdbc;
  private final JdbcTemplate template;
  private final EntityManager em;

  LotRepository(JdbcClient jdbc, JdbcTemplate template, EntityManager em) {
    this.em = em;
    this.jdbc = jdbc;
    this.template = template;
  }

  /** A timestamp as a value the driver binds to a {@code timestamptz}. */
  static OffsetDateTime ts(Instant t) { return OffsetDateTime.ofInstant(t, ZoneOffset.UTC); }

  static Lot lot(ResultSet r) throws SQLException {
    LocalDate expiry = r.getObject("expiry_date", LocalDate.class);
    return new Lot(r.getObject("id", UUID.class), r.getObject("product_id", UUID.class), r.getObject("received_at", OffsetDateTime.class).toInstant(),
        r.getInt("qty_received"), r.getInt("qty_remaining"), r.getBigDecimal("unit_cost"), r.getBoolean("cost_known"),
        (Long) r.getObject("mrp_paise"), (Long) r.getObject("price_paise"), r.getString("batch_no"), expiry, r.getString("source"));
  }

  /**
   * The lots with stock of these products, locked until the transaction ends. Locked in product-id then received order, inside the
   * callers' own product-id ordering, so two sales cannot wait on each other.
   */
  public Map<UUID, ProductLots> lockOpen(Collection<UUID> productIds) {
    if (productIds.isEmpty()) return Map.of();
    Map<UUID, List<Lot>> lots = new LinkedHashMap<>();
    Map<UUID, boolean[]> flags = new LinkedHashMap<>();
    Map<UUID, long[]> prices = new LinkedHashMap<>();
    jdbc.sql("""
        select l.*, p.lot_priced, p.track_expiry, p.price_paise as p_price, p.mrp_paise as p_mrp
        from stock_lot l join product p on p.id = l.product_id
        where l.product_id in (:ids) and l.qty_remaining > 0
        order by l.product_id, l.received_at, l.id
        for update of l""").param("ids", productIds).query(r -> {
          Lot l = lot(r);
          lots.computeIfAbsent(l.productId(), _ -> new ArrayList<>()).add(l);
          flags.putIfAbsent(l.productId(), new boolean[] {r.getBoolean("lot_priced"), r.getBoolean("track_expiry")});
          prices.putIfAbsent(l.productId(), new long[] {r.getLong("p_price"), r.getLong("p_mrp")});
        });
    Map<UUID, ProductLots> out = new LinkedHashMap<>();
    lots.forEach((id, list) -> out.put(id, new ProductLots(id, flags.get(id)[0], flags.get(id)[1], prices.get(id)[0], prices.get(id)[1], list)));
    return out;
  }

  /** The lots with stock of one product, in the order they are used, without locking (for display). */
  public ProductLots openLots(UUID productId) {
    List<Lot> list = jdbc.sql("select * from stock_lot where product_id = :p and qty_remaining > 0 order by received_at, id")
        .param("p", productId).query((r, _) -> lot(r)).list();
    boolean[] f = flags(productId);
    return new ProductLots(productId, f[0], f[1], 0, 0, list);
  }

  /** {lotPriced, trackExpiry} of a product; both false when the product is unknown. */
  public boolean[] flags(UUID productId) {
    return findFlags(productId).orElse(new boolean[] {false, false});
  }

  /** {lotPriced, trackExpiry} of a product, or empty when the product is unknown. */
  public java.util.Optional<boolean[]> findFlags(UUID productId) {
    return jdbc.sql("select lot_priced, track_expiry from product where id = :p").param("p", productId)
        .query((r, _) -> new boolean[] {r.getBoolean(1), r.getBoolean(2)}).optional();
  }

  /** The lots with stock of the lot-priced products among these, in use order, without locking (for prices at the counter and in quotes). */
  public Map<UUID, ProductLots> openLotPriced(Collection<UUID> productIds) {
    if (productIds.isEmpty()) return Map.of();
    Map<UUID, List<Lot>> lots = new LinkedHashMap<>();
    Map<UUID, Boolean> expiry = new LinkedHashMap<>();
    jdbc.sql("""
        select l.*, p.track_expiry from stock_lot l join product p on p.id = l.product_id
        where p.id in (:ids) and p.lot_priced and l.qty_remaining > 0 order by l.product_id, l.received_at, l.id""")
        .param("ids", productIds).query(r -> {
          Lot l = lot(r);
          lots.computeIfAbsent(l.productId(), _ -> new ArrayList<>()).add(l);
          expiry.put(l.productId(), r.getBoolean("track_expiry"));
        });
    Map<UUID, ProductLots> out = new LinkedHashMap<>();
    lots.forEach((id, list) -> out.put(id, new ProductLots(id, true, expiry.get(id), 0, 0, list)));
    return out;
  }

  /** Subtracts units from lots (one round trip); a lot that has less than asked changes nothing and the call fails. */
  public void decrement(Map<UUID, Integer> byLot) {
    if (byLot.isEmpty()) return;
    List<Object[]> args = byLot.entrySet().stream().map(e -> new Object[] {e.getValue(), e.getKey(), e.getValue()}).toList();
    int[] done = template.batchUpdate("update stock_lot set qty_remaining = qty_remaining - ? where id = ? and qty_remaining >= ?", args);
    for (int n : done) if (n != 1 && n != -2) throw new IllegalStateException("a lot had less stock than expected");
  }

  /** Adds units to lots and records them as returned on their allocations (two batches). */
  public void putBack(List<Back> backs, Instant now) {
    if (backs.isEmpty()) return;
    template.batchUpdate("update stock_lot set qty_remaining = qty_remaining + ? where id = ?",
        backs.stream().map(b -> new Object[] {b.qty(), b.lotId()}).toList());
    List<Object[]> allocs = backs.stream().filter(b -> b.allocationId() != null).map(b -> new Object[] {b.qty(), b.costPaise(), b.allocationId()}).toList();
    if (!allocs.isEmpty()) {
      template.batchUpdate("update stock_allocation set returned_qty = returned_qty + ?, returned_cost_paise = returned_cost_paise + ? where id = ?", allocs);
    }
  }

  /** Writes the records of units taken from lots (one round trip). */
  public void insertAllocations(List<Allocation> rows, Instant now) {
    if (rows.isEmpty()) return;
    template.batchUpdate("insert into stock_allocation (id, lot_id, product_id, ref_type, ref_id, qty, unit_cost, cost_paise, created_at) "
        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)", rows.stream().map(a -> new Object[] {a.id(), a.lotId(), a.productId(), a.refType(), a.refId(), a.qty(),
        a.unitCost(), a.costPaise(), ts(now)}).toList());
  }

  /** Adds a lot. */
  public void insertLot(Lot l, UUID purchaseItemId, Instant now) {
    em.flush(); // a product created in this same transaction must be in the database before its first lot
    jdbc.sql("""
        insert into stock_lot (id, product_id, purchase_item_id, received_at, qty_received, qty_remaining, unit_cost, cost_known, mrp_paise,
          price_paise, batch_no, expiry_date, source, created_at)
        values (:id, :p, :pi, :rec, :q, :q, :cost, :known, :mrp, :price, :batch, :exp, :src, :now)""")
        .param("id", l.id()).param("p", l.productId()).param("pi", purchaseItemId).param("rec", ts(l.receivedAt())).param("q", l.qtyReceived())
        .param("cost", l.unitCost()).param("known", l.costKnown()).param("mrp", l.mrpPaise()).param("price", l.pricePaise())
        .param("batch", l.batchNo()).param("exp", l.expiryDate()).param("src", l.source()).param("now", ts(now)).update();
  }

  /** One lot, locked until the transaction ends. */
  public Optional<Lot> lockLot(UUID lotId) {
    return jdbc.sql("select * from stock_lot where id = :id for update").param("id", lotId).query((r, _) -> lot(r)).optional();
  }

  /** The allocations of these documents' lines that still have units to give back, oldest first, locked. */
  public List<Allocation> openAllocations(String refType, Collection<UUID> refIds) {
    if (refIds.isEmpty()) return List.of();
    return jdbc.sql("select * from stock_allocation where ref_type = :t and ref_id in (:ids) and returned_qty < qty order by created_at, id for update")
        .param("t", refType).param("ids", refIds).query((r, _) -> allocation(r)).list();
  }

  /** The allocations of one purchase's lots-first return: the lots of that purchase with stock, oldest first, locked. */
  public List<Lot> purchaseLots(UUID purchaseId, UUID productId) {
    return jdbc.sql("""
        select l.* from stock_lot l join purchase_item pi on pi.id = l.purchase_item_id
        where pi.purchase_id = :pu and l.product_id = :p and l.qty_remaining > 0 order by l.received_at, l.id for update of l""")
        .param("pu", purchaseId).param("p", productId).query((r, _) -> lot(r)).list();
  }

  static Allocation allocation(ResultSet r) throws SQLException {
    return new Allocation(r.getObject("id", UUID.class), r.getObject("lot_id", UUID.class), r.getObject("product_id", UUID.class),
        r.getString("ref_type"), r.getObject("ref_id", UUID.class), r.getInt("qty"), r.getBigDecimal("unit_cost"), r.getLong("cost_paise"),
        r.getInt("returned_qty"), r.getLong("returned_cost_paise"));
  }

  /** The unit cost of the most recent lot of each product whose cost is known; a product without one is absent. */
  public Map<UUID, BigDecimal> lastKnownCost(Collection<UUID> productIds) {
    if (productIds.isEmpty()) return Map.of();
    Map<UUID, BigDecimal> out = new LinkedHashMap<>();
    jdbc.sql("select distinct on (product_id) product_id, unit_cost from stock_lot where product_id in (:ids) and cost_known "
        + "order by product_id, received_at desc, id").param("ids", productIds)
        .query(r -> { out.put(r.getObject(1, UUID.class), r.getBigDecimal(2)); });
    return out;
  }

  /** Sets a lot-priced product's selling price and MRP to those of its current lot; returns whether anything changed. */
  public boolean setProductPrice(UUID productId, long pricePaise, long mrpPaise, Instant now) {
    return jdbc.sql("update product set price_paise = :pr, mrp_paise = :mrp, updated_at = :now where id = :p and (price_paise <> :pr or mrp_paise <> :mrp)")
        .param("pr", pricePaise).param("mrp", mrpPaise).param("now", ts(now)).param("p", productId).update() > 0;
  }

  /** The price and MRP now on the product. {price, mrp}. */
  public long[] productPrice(UUID productId) {
    return jdbc.sql("select price_paise, mrp_paise from product where id = :p").param("p", productId)
        .query((r, _) -> new long[] {r.getLong(1), r.getLong(2)}).optional().orElse(new long[] {0, 0});
  }

  /** Switches lot pricing and expiry tracking of a product; returns whether the product exists. */
  public boolean setFlags(UUID productId, boolean lotPriced, boolean trackExpiry, Instant now) {
    return jdbc.sql("update product set lot_priced = :lp, track_expiry = :te, updated_at = :now where id = :p")
        .param("lp", lotPriced).param("te", trackExpiry).param("now", ts(now)).param("p", productId).update() > 0;
  }
}
