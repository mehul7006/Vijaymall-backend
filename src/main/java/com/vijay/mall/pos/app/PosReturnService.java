package com.vijay.mall.pos.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.inventory.app.DefectiveService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.ReturnInput;
import com.vijay.mall.pos.app.PosViews.ReturnLine;
import com.vijay.mall.pos.domain.GstCalculator;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.pos.domain.Money;
import com.vijay.mall.pos.domain.PaymentRules;
import com.vijay.mall.pos.domain.PosRecords.PaymentRecord;
import com.vijay.mall.pos.domain.PosRecords.ReturnItemRecord;
import com.vijay.mall.pos.domain.PosRecords.ReturnRecord;
import com.vijay.mall.pos.domain.PosRecords.ReturnedSoFar;
import com.vijay.mall.pos.domain.PosRecords.SaleItemRecord;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.ReturnMath;
import com.vijay.mall.pos.domain.TaxSplit;
import com.vijay.mall.pos.infra.InvoiceCounterRepository;
import com.vijay.mall.pos.infra.PosSaleRepository;
import com.vijay.mall.wallet.app.WalletService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Item-level returns against a bill: credit note, stock back, refund, coins adjusted. */
@Service
public class PosReturnService {
  private static final Logger log = LoggerFactory.getLogger(PosReturnService.class);

  private final PosSaleRepository sales;
  private final InventoryService inventory;
  private final WalletService wallet;
  private final ShopService shop;
  private final InvoiceCounterRepository counters;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final CreditService credit;

  /** Creates the service. */
  public PosReturnService(PosSaleRepository sales, InventoryService inventory, WalletService wallet, ShopService shop,
                          InvoiceCounterRepository counters, AuditService audit, TransactionTemplate tx, Clock clock,
                          CreditService credit) {
    this.credit = credit;
    this.sales = sales;
    this.inventory = inventory;
    this.wallet = wallet;
    this.shop = shop;
    this.counters = counters;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  /** Cashiers may take back goods from bills this recent; older bills need an admin (0 = no limit). */
  private int cashierReturnDays = 7;
  private RuntimeSettings settings;
  private DefectiveService defective;

  /** Optional (plain unit tests run without it): where goods returned as defective are kept. */
  @Autowired(required = false)
  void setDefective(DefectiveService defective) { this.defective = defective; }

  /** Optional (plain unit tests run without it): the admin can change the return window from the config screen. */
  @Autowired(required = false)
  void setSettings(RuntimeSettings settings) { this.settings = settings; }

  private int returnDays() {
    return settings != null ? settings.getInt(Settings.POS_CASHIER_RETURN_DAYS) : cashierReturnDays;
  }

  @Autowired(required = false)
  void setCashierReturnDays(@Value("${app.pos.cashier-return-days:7}") int days) {
    this.cashierReturnDays = days;
  }

  /** Returns by an admin, who is not held to the cashier's return window. */
  public ReturnRecord refund(UUID cashierId, UUID saleId, String idemKey, ReturnInput in) {
    return refund(cashierId, true, saleId, idemKey, in);
  }

  /**
   * Takes goods back against a bill, once per idempotency key (a repeat returns the first return). In one transaction
   * it checks the quantities still returnable, computes the refund and its GST share, puts stock back or into
   * defective stock, claws back earned coins and restores redeemed coins, first reduces any credit the customer still
   * owes for the bill, pays out the rest and issues the credit note.
   *
   * @param cashierId the user taking the return
   * @param admin true for a manager, who is not held to the cashier return window
   * @param saleId the bill the goods came from
   * @param idemKey client-supplied key; required
   * @param in the lines, reason and refund payments (default is all cash)
   * @throws ApiException 422 {@code RETURN_NEEDS_ADMIN} for a bill older than the cashier window, 409 {@code
   *     RETURN_EXCEEDS_SOLD}, 400 for a bad line or refund split, 404 for an unknown bill
   */
  public ReturnRecord refund(UUID cashierId, boolean admin, UUID saleId, String idemKey, ReturnInput in) {
    if (idemKey == null || idemKey.isBlank()) throw ApiException.validation("Idempotency-Key", "header is required");
    Optional<ReturnRecord> existing = sales.findReturnByIdem(idemKey);
    if (existing.isPresent()) return existing.get();
    try {
      UUID id = tx.execute(s -> doRefund(cashierId, admin, saleId, idemKey, in));
      return sales.returnsOf(saleId).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    } catch (DataIntegrityViolationException e) {
      return sales.findReturnByIdem(idemKey).orElseThrow(() -> e);
    }
  }

  /** The returns already made against a bill, oldest first. */
  public List<ReturnRecord> returnsOf(UUID saleId) { return sales.returnsOf(saleId); }

  private UUID doRefund(UUID cashierId, boolean admin, UUID saleId, String idemKey, ReturnInput in) {
    SaleRecord sale = sales.lockAndFind(saleId).orElseThrow(() -> ApiException.notFound("Bill"));
    if (!admin && returnDays() > 0
        && sale.createdAt().isBefore(clock.instant().minus(returnDays(), ChronoUnit.DAYS))) {
      throw ApiException.unprocessable("RETURN_NEEDS_ADMIN", "This bill is older than " + returnDays()
          + " days: an admin must take the return", Map.of("days", returnDays()));
    }
    Map<UUID, SaleItemRecord> byId = sale.items().stream().collect(Collectors.toMap(SaleItemRecord::id, Function.identity()));
    Map<UUID, Integer> wanted = merge(in.items(), byId);
    ReturnedSoFar prior = sales.returnedSoFar(saleId, sale.items());

    List<ReturnItemRecord> items = wanted.entrySet().stream().map(e -> {
      SaleItemRecord i = byId.get(e.getKey());
      int qty = e.getValue();
      if (qty > i.returnableQty()) {
        throw ApiException.conflict("RETURN_EXCEEDS_SOLD", "More than what is left to return",
            Map.of("saleItemId", i.id().toString(), "returnable", i.returnableQty()));
      }
      long refund = ReturnMath.shareOfReturn(i.netPaise(), i.qty(), i.returnedQty(), qty);
      TaxSplit tax = GstCalculator.split(refund, i.gstPercent());
      return new ReturnItemRecord(i.id(), qty, refund, tax.taxable(), tax.cgst(), tax.sgst());
    }).toList();

    long refundTotal = items.stream().mapToLong(ReturnItemRecord::refundPaise).sum();
    long grossReturned = wanted.entrySet().stream()
        .mapToLong(e -> ReturnMath.shareOfReturn(byId.get(e.getKey()).linePaise(), byId.get(e.getKey()).qty(),
            byId.get(e.getKey()).returnedQty(), e.getValue())).sum();
    long cumulativeGross = prior.grossPaise() + grossReturned;
    int claw = sale.customerId() == null ? 0
        : ReturnMath.coinsDelta(sale.coinsEarned(), cumulativeGross, sale.subtotalPaise(), prior.coinsClawedBack());
    int restore = sale.customerId() == null ? 0
        : ReturnMath.coinsDelta(sale.coinsUsed(), cumulativeGross, sale.subtotalPaise(), prior.coinsRestored());

    Instant now = clock.instant();
    // Goods bought on credit come back first against what the customer still owes; only the rest is paid out.
    long creditOnBill = sale.creditPaise();
    long creditAdjust = sale.customerId() == null || creditOnBill == 0 ? 0
        : credit.returnAdjustment(sale.customerId(), saleId, creditOnBill, refundTotal, now);
    long payOut = refundTotal - creditAdjust;

    List<Pay> refunds = in.refunds() == null || in.refunds().isEmpty() ? List.of(new Pay("CASH", payOut, null)) : in.refunds();
    if (payOut == 0) refunds = List.of();
    try {
      PaymentRules.checkRefund(refunds.stream().map(p -> new PaymentRules.Line(p.mode(), p.amountPaise(), p.reference())).toList(),
          payOut);
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("refunds", creditAdjust > 0
          ? e.getMessage() + " (Rs " + Money.plain(creditAdjust) + " of this return reduces the customer's credit)"
          : e.getMessage());
    }

    UUID returnId = UUID.randomUUID();
    items.forEach(i -> {
      if (!sales.addReturned(i.saleItemId(), i.qty())) {
        throw ApiException.conflict("RETURN_EXCEEDS_SOLD", "More than what is left to return", Map.of("saleItemId", i.saleItemId().toString()));
      }
    });
    // units marked restock=false are damaged: kept apart as defective stock, the rest go back on the shelf
    Map<UUID, Integer> defectiveQty = new HashMap<>();
    if (in.items() != null) in.items().forEach(l -> {
      if (l != null && l.saleItemId() != null && Boolean.FALSE.equals(l.restock())) defectiveQty.merge(l.saleItemId(), l.qty(), Integer::sum);
    });
    List<InventoryService.Item> backOnShelf = new ArrayList<>();
    List<InventoryService.Item> damaged = new ArrayList<>();
    for (ReturnItemRecord i : items) {
      int bad = Math.min(i.qty(), defectiveQty.getOrDefault(i.saleItemId(), 0));
      UUID product = byId.get(i.saleItemId()).productId();
      if (i.qty() - bad > 0) backOnShelf.add(new InventoryService.Item(product, i.qty() - bad));
      if (bad > 0) damaged.add(new InventoryService.Item(product, bad));
    }
    if (!backOnShelf.isEmpty()) inventory.release(backOnShelf, returnId, "POS_RETURN");
    if (defective != null && !damaged.isEmpty()) defective.record(damaged, "POS_RETURN", returnId, cashierId, in.reason());
    else if (!damaged.isEmpty()) inventory.release(damaged, returnId, "POS_RETURN"); // no defective ledger wired (plain unit tests): behave as before
    int clawed = sale.customerId() == null ? 0 : wallet.clawback(sale.customerId(), claw, returnId);
    if (sale.customerId() != null) wallet.restore(sale.customerId(), restore, returnId);

    String fy = InvoiceNumbers.financialYear(now);
    String noteNo = InvoiceNumbers.format(shop.get().invoicePrefix() + "-CN", fy, counters.next("CREDIT_NOTE", fy));
    long taxable = items.stream().mapToLong(ReturnItemRecord::taxablePaise).sum();
    long cgst = items.stream().mapToLong(ReturnItemRecord::cgstPaise).sum();
    long sgst = items.stream().mapToLong(ReturnItemRecord::sgstPaise).sum();
    List<PaymentRecord> refundRecords = refunds.stream().map(p -> new PaymentRecord(p.mode(), p.amountPaise(), p.reference())).toList();
    sales.insertReturn(new ReturnRecord(returnId, noteNo, saleId, cashierId, in.reason() == null ? "" : in.reason().strip(),
        refundTotal, taxable, cgst, sgst, clawed, restore, now, items, refundRecords, creditAdjust), idemKey);
    if (creditAdjust > 0) credit.recordReturn(sale.customerId(), saleId, returnId, noteNo, creditAdjust, cashierId, now);
    audit.log(cashierId, "RETURN", "pos_return", returnId, noteNo + " bill=" + sale.invoiceNo() + " refund=" + refundTotal);
    log.info("POS return note={} bill={} cashier={} refund={} creditReduced={} coinsClawed={} coinsRestored={}", noteNo,
        sale.invoiceNo(), cashierId, refundTotal, creditAdjust, clawed, restore);
    return returnId;
  }

  /** Adds up duplicate lines and checks every line belongs to the bill. */
  private static Map<UUID, Integer> merge(List<ReturnLine> lines, Map<UUID, SaleItemRecord> byId) {
    if (lines == null || lines.isEmpty()) throw ApiException.validation("items", "choose at least one item to return");
    Map<UUID, Integer> merged = new LinkedHashMap<>();
    lines.forEach(l -> {
      if (l.saleItemId() == null || !byId.containsKey(l.saleItemId())) throw ApiException.validation("saleItemId", "item is not on this bill");
      if (l.qty() <= 0) throw ApiException.validation("qty", "quantity must be positive");
      merged.merge(l.saleItemId(), l.qty(), Integer::sum);
    });
    return merged;
  }
}
