package com.vijay.mall.pos.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.ReturnInput;
import com.vijay.mall.pos.app.PosViews.ReturnLine;
import com.vijay.mall.pos.domain.GstCalculator;
import com.vijay.mall.pos.domain.InvoiceNumbers;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  public PosReturnService(PosSaleRepository sales, InventoryService inventory, WalletService wallet, ShopService shop,
                          InvoiceCounterRepository counters, AuditService audit, TransactionTemplate tx, Clock clock) {
    this.sales = sales;
    this.inventory = inventory;
    this.wallet = wallet;
    this.shop = shop;
    this.counters = counters;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  public ReturnRecord refund(UUID cashierId, UUID saleId, String idemKey, ReturnInput in) {
    if (idemKey == null || idemKey.isBlank()) throw ApiException.validation("Idempotency-Key", "header is required");
    Optional<ReturnRecord> existing = sales.findReturnByIdem(idemKey);
    if (existing.isPresent()) return existing.get();
    try {
      UUID id = tx.execute(s -> doRefund(cashierId, saleId, idemKey, in));
      return sales.returnsOf(saleId).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    } catch (DataIntegrityViolationException e) {
      return sales.findReturnByIdem(idemKey).orElseThrow(() -> e);
    }
  }

  public List<ReturnRecord> returnsOf(UUID saleId) { return sales.returnsOf(saleId); }

  private UUID doRefund(UUID cashierId, UUID saleId, String idemKey, ReturnInput in) {
    SaleRecord sale = sales.lockAndFind(saleId).orElseThrow(() -> ApiException.notFound("Bill"));
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

    List<Pay> refunds = in.refunds() == null || in.refunds().isEmpty() ? List.of(new Pay("CASH", refundTotal, null)) : in.refunds();
    if (refundTotal == 0) refunds = List.of();
    try {
      PaymentRules.checkRefund(refunds.stream().map(p -> new PaymentRules.Line(p.mode(), p.amountPaise(), p.reference())).toList(),
          refundTotal);
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("refunds", e.getMessage());
    }

    UUID returnId = UUID.randomUUID();
    Instant now = clock.instant();
    items.forEach(i -> {
      if (!sales.addReturned(i.saleItemId(), i.qty())) {
        throw ApiException.conflict("RETURN_EXCEEDS_SOLD", "More than what is left to return", Map.of("saleItemId", i.saleItemId().toString()));
      }
    });
    inventory.release(items.stream().map(i -> new InventoryService.Item(byId.get(i.saleItemId()).productId(), i.qty())).toList(),
        returnId, "POS_RETURN");
    int clawed = sale.customerId() == null ? 0 : wallet.clawback(sale.customerId(), claw, returnId);
    if (sale.customerId() != null) wallet.restore(sale.customerId(), restore, returnId);

    String fy = InvoiceNumbers.financialYear(now);
    String noteNo = InvoiceNumbers.format(shop.get().invoicePrefix() + "-CN", fy, counters.next("CREDIT_NOTE", fy));
    long taxable = items.stream().mapToLong(ReturnItemRecord::taxablePaise).sum();
    long cgst = items.stream().mapToLong(ReturnItemRecord::cgstPaise).sum();
    long sgst = items.stream().mapToLong(ReturnItemRecord::sgstPaise).sum();
    List<PaymentRecord> refundRecords = refunds.stream().map(p -> new PaymentRecord(p.mode(), p.amountPaise(), p.reference())).toList();
    sales.insertReturn(new ReturnRecord(returnId, noteNo, saleId, cashierId, in.reason() == null ? "" : in.reason().strip(),
        refundTotal, taxable, cgst, sgst, clawed, restore, now, items, refundRecords), idemKey);
    audit.log(cashierId, "RETURN", "pos_return", returnId, noteNo + " bill=" + sale.invoiceNo() + " refund=" + refundTotal);
    log.info("POS return note={} bill={} cashier={} refund={} coinsClawed={} coinsRestored={}", noteNo, sale.invoiceNo(), cashierId,
        refundTotal, clawed, restore);
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
