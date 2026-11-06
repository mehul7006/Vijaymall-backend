package com.vijay.mall.pos.infra;

import com.vijay.mall.pos.domain.PosRecords.PaymentRecord;
import com.vijay.mall.pos.domain.PosRecords.ReturnItemRecord;
import com.vijay.mall.pos.domain.PosRecords.ReturnRecord;
import com.vijay.mall.pos.domain.PosRecords.ReturnedSoFar;
import com.vijay.mall.pos.domain.PosRecords.SaleItemRecord;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.SoldBy;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Bills, their lines and payments, and the credit notes raised against them. */
@Repository
public class PosSaleRepository {
  private final PosSaleJpa sales;
  private final PosSaleItemJpa items;
  private final PosPaymentJpa payments;
  private final PosReturnJpa returns;
  private final PosReturnItemJpa returnItems;
  private final PosRefundJpa refunds;

  PosSaleRepository(PosSaleJpa sales, PosSaleItemJpa items, PosPaymentJpa payments,
                    PosReturnJpa returns, PosReturnItemJpa returnItems, PosRefundJpa refunds) {
    this.sales = sales;
    this.items = items;
    this.payments = payments;
    this.returns = returns;
    this.returnItems = returnItems;
    this.refunds = refunds;
  }

  // ---------- write ----------
  @Transactional
  public void insert(SaleRecord s, String idemKey) {
    PosSaleEntity e = new PosSaleEntity();
    e.id = s.id();
    e.invoiceNo = s.invoiceNo();
    e.idemKey = idemKey;
    e.cashierId = s.cashierId();
    e.customerId = s.customerId();
    e.customerMobile = s.customerMobile();
    e.subtotalPaise = s.subtotalPaise();
    e.couponCode = s.couponCode();
    e.couponDiscountPaise = s.couponDiscountPaise();
    e.coinsUsed = s.coinsUsed();
    e.coinDiscountPaise = s.coinDiscountPaise();
    e.totalPaise = s.totalPaise();
    e.taxablePaise = s.taxablePaise();
    e.cgstPaise = s.cgstPaise();
    e.sgstPaise = s.sgstPaise();
    e.coinsEarned = s.coinsEarned();
    e.tenderedPaise = s.tenderedPaise();
    e.changePaise = s.changePaise();
    e.paymentType = s.paymentType();
    e.status = "COMPLETED";
    e.createdAt = s.createdAt();
    sales.saveAndFlush(e);
    s.items().forEach(i -> {
      PosSaleItemEntity r = new PosSaleItemEntity();
      r.id = i.id();
      r.saleId = s.id();
      r.productId = i.productId();
      r.name = i.name();
      r.soldBy = i.soldBy().name();
      r.qty = i.qty();
      r.unitPricePaise = i.unitPricePaise();
      r.linePaise = i.linePaise();
      r.discountPaise = i.discountPaise();
      r.netPaise = i.netPaise();
      r.gstPercent = i.gstPercent();
      r.hsn = i.hsn();
      r.taxablePaise = i.taxablePaise();
      r.cgstPaise = i.cgstPaise();
      r.sgstPaise = i.sgstPaise();
      r.returnedQty = 0;
      items.save(r);
    });
    s.payments().forEach(p -> {
      PosPaymentEntity r = new PosPaymentEntity();
      r.id = UUID.randomUUID();
      r.saleId = s.id();
      r.mode = p.mode();
      r.amountPaise = p.amountPaise();
      r.reference = p.reference();
      payments.save(r);
    });
    items.flush();
  }

  /** Takes the row lock of a bill so two returns against it run one after another. */
  @Transactional
  public Optional<SaleRecord> lockAndFind(UUID id) { return sales.lockById(id).map(e -> assemble(List.of(e)).get(0)); }

  /** Counts returned units; false when more than what was sold would come back. */
  @Transactional
  public boolean addReturned(UUID saleItemId, int qty) { return items.addReturned(saleItemId, qty) > 0; }

  @Transactional
  public void insertReturn(ReturnRecord r, String idemKey) {
    PosReturnEntity e = new PosReturnEntity();
    e.id = r.id();
    e.creditNoteNo = r.creditNoteNo();
    e.idemKey = idemKey;
    e.saleId = r.saleId();
    e.cashierId = r.cashierId();
    e.reason = r.reason();
    e.refundPaise = r.refundPaise();
    e.taxablePaise = r.taxablePaise();
    e.cgstPaise = r.cgstPaise();
    e.sgstPaise = r.sgstPaise();
    e.coinsClawedBack = r.coinsClawedBack();
    e.coinsRestored = r.coinsRestored();
    e.creditAdjustPaise = r.creditAdjustedPaise();
    e.createdAt = r.createdAt();
    returns.saveAndFlush(e);
    r.items().forEach(i -> {
      PosReturnItemEntity x = new PosReturnItemEntity();
      x.id = UUID.randomUUID();
      x.returnId = r.id();
      x.saleItemId = i.saleItemId();
      x.qty = i.qty();
      x.refundPaise = i.refundPaise();
      x.taxablePaise = i.taxablePaise();
      x.cgstPaise = i.cgstPaise();
      x.sgstPaise = i.sgstPaise();
      returnItems.save(x);
    });
    r.refunds().forEach(p -> {
      PosRefundPaymentEntity x = new PosRefundPaymentEntity();
      x.id = UUID.randomUUID();
      x.returnId = r.id();
      x.mode = p.mode();
      x.amountPaise = p.amountPaise();
      x.reference = p.reference();
      refunds.save(x);
    });
    returnItems.flush();
  }

  // ---------- read ----------
  public Optional<SaleRecord> find(UUID id) { return sales.findById(id).map(e -> assemble(List.of(e)).get(0)); }

  public Optional<SaleRecord> findByIdem(String idemKey) {
    return sales.findByIdemKey(idemKey).map(e -> assemble(List.of(e)).get(0));
  }

  /** Invoice numbers of counter bills and credit note numbers of returns, for the ids given. */
  public java.util.Map<UUID, String> documentNumbers(java.util.Collection<UUID> ids) {
    java.util.Map<UUID, String> out = new java.util.HashMap<>();
    if (ids.isEmpty()) return out;
    sales.invoiceNumbers(ids).forEach(r -> out.put((UUID) r[0], (String) r[1]));
    returns.creditNoteNumbers(ids).forEach(r -> out.put((UUID) r[0], (String) r[1]));
    return out;
  }

  public Optional<SaleRecord> findByInvoiceNo(String invoiceNo) {
    return sales.findByInvoiceNo(invoiceNo).map(e -> assemble(List.of(e)).get(0));
  }

  public List<SaleRecord> byMobile(String mobile) {
    return assemble(sales.findTop50ByCustomerMobileOrderByCreatedAtDesc(mobile));
  }

  public List<SaleRecord> between(Instant from, Instant to) {
    return assemble(sales.findTop200ByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(from, to));
  }

  private List<SaleRecord> assemble(List<PosSaleEntity> list) {
    if (list.isEmpty()) return List.of();
    List<UUID> ids = list.stream().map(s -> s.id).toList();
    Map<UUID, List<SaleItemRecord>> lines = items.findBySaleIdIn(ids).stream()
        .collect(Collectors.groupingBy(i -> i.saleId, Collectors.mapping(PosSaleRepository::toItem, Collectors.toList())));
    Map<UUID, List<PaymentRecord>> pays = payments.findBySaleIdIn(ids).stream()
        .collect(Collectors.groupingBy(p -> p.saleId,
            Collectors.mapping(p -> new PaymentRecord(p.mode, p.amountPaise, p.reference), Collectors.toList())));
    return list.stream().map(s -> new SaleRecord(s.id, s.invoiceNo, s.cashierId, s.customerId, s.customerMobile,
        s.subtotalPaise, s.couponCode, s.couponDiscountPaise, s.coinsUsed, s.coinDiscountPaise, s.totalPaise,
        s.taxablePaise, s.cgstPaise, s.sgstPaise, s.coinsEarned, s.tenderedPaise, s.changePaise, s.createdAt,
        lines.getOrDefault(s.id, List.of()), pays.getOrDefault(s.id, List.of()), s.paymentType)).toList();
  }

  private static SaleItemRecord toItem(PosSaleItemEntity i) {
    return new SaleItemRecord(i.id, i.productId, i.name, SoldBy.valueOf(i.soldBy), i.qty, i.unitPricePaise, i.linePaise,
        i.discountPaise, i.netPaise, i.gstPercent, i.hsn, i.taxablePaise, i.cgstPaise, i.sgstPaise, i.returnedQty);
  }

  public Optional<ReturnRecord> findReturnByIdem(String idemKey) {
    return returns.findByIdemKey(idemKey).map(e -> assembleReturns(List.of(e)).get(0));
  }

  public List<ReturnRecord> returnsOf(UUID saleId) { return assembleReturns(returns.findBySaleIdOrderByCreatedAt(saleId)); }

  /** Gross value and coins already moved by earlier credit notes of a bill. */
  public ReturnedSoFar returnedSoFar(UUID saleId, Collection<SaleItemRecord> saleItems) {
    List<ReturnRecord> prior = returnsOf(saleId);
    Map<UUID, SaleItemRecord> byId = saleItems.stream().collect(Collectors.toMap(SaleItemRecord::id, i -> i));
    long gross = prior.stream().flatMap(r -> r.items().stream())
        .mapToLong(ri -> com.vijay.mall.pos.domain.ReturnMath.roundShare(byId.get(ri.saleItemId()).linePaise(), ri.qty(),
            byId.get(ri.saleItemId()).qty())).sum();
    return new ReturnedSoFar(gross, prior.stream().mapToInt(ReturnRecord::coinsClawedBack).sum(),
        prior.stream().mapToInt(ReturnRecord::coinsRestored).sum());
  }

  private List<ReturnRecord> assembleReturns(List<PosReturnEntity> list) {
    if (list.isEmpty()) return List.of();
    List<UUID> ids = list.stream().map(r -> r.id).toList();
    Map<UUID, List<ReturnItemRecord>> its = returnItems.findByReturnIdIn(ids).stream()
        .collect(Collectors.groupingBy(i -> i.returnId, Collectors.mapping(
            i -> new ReturnItemRecord(i.saleItemId, i.qty, i.refundPaise, i.taxablePaise, i.cgstPaise, i.sgstPaise),
            Collectors.toList())));
    Map<UUID, List<PaymentRecord>> rf = refunds.findByReturnIdIn(ids).stream()
        .collect(Collectors.groupingBy(p -> p.returnId,
            Collectors.mapping(p -> new PaymentRecord(p.mode, p.amountPaise, p.reference), Collectors.toList())));
    return list.stream().map(r -> new ReturnRecord(r.id, r.creditNoteNo, r.saleId, r.cashierId, r.reason, r.refundPaise,
        r.taxablePaise, r.cgstPaise, r.sgstPaise, r.coinsClawedBack, r.coinsRestored, r.createdAt,
        its.getOrDefault(r.id, List.of()), rf.getOrDefault(r.id, List.of()), r.creditAdjustPaise)).toList();
  }
}
