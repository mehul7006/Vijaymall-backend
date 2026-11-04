package com.vijay.mall.pos.app;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.app.QuoteService;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.pos.app.PosViews.BillLine;
import com.vijay.mall.pos.app.PosViews.BillView;
import com.vijay.mall.pos.app.PosViews.Cart;
import com.vijay.mall.pos.app.PosViews.HoldView;
import com.vijay.mall.pos.app.PosViews.Item;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.SaleInput;
import com.vijay.mall.pos.domain.BillCalculator;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.pos.domain.PaymentRules;
import com.vijay.mall.pos.domain.PosRecords.HoldRecord;
import com.vijay.mall.pos.domain.PosRecords.PaymentRecord;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.SaleItemRecord;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.ReceiptData;
import com.vijay.mall.pos.domain.ReceiptLine;
import com.vijay.mall.pos.domain.ReceiptText;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.UpiLink;
import com.vijay.mall.pos.infra.InvoiceCounterRepository;
import com.vijay.mall.pos.infra.PosHoldRepository;
import com.vijay.mall.pos.infra.PosSaleRepository;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Counter billing: price a basket, take payment, keep bills, hold baskets, print receipts. */
@Service
public class PosService implements com.vijay.mall.common.ReferenceLookup {
  private static final Logger log = LoggerFactory.getLogger(PosService.class);
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
  private static final int MAX_UNITS = 9_999;
  private static final int MAX_GRAMS = 100_000;

  private record Priced(List<PricedLine> lines, List<BillCalculator.Draft> drafts) {}

  private final PosCatalogService catalog;
  private final QuoteService quotes;
  private final InventoryService inventory;
  private final WalletService wallet;
  private final PromotionService promotions;
  private final UserRepository users;
  private final ShopService shop;
  private final PosSaleRepository sales;
  private final PosHoldRepository holds;
  private final InvoiceCounterRepository counters;
  private final AuditService audit;
  private final JsonMapper json;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final CreditService credit;

  public PosService(PosCatalogService catalog, QuoteService quotes, InventoryService inventory, WalletService wallet,
                    PromotionService promotions, UserRepository users, ShopService shop, PosSaleRepository sales,
                    PosHoldRepository holds, InvoiceCounterRepository counters, AuditService audit, JsonMapper json,
                    TransactionTemplate tx, Clock clock, CreditService credit) {
    this.credit = credit;
    this.catalog = catalog;
    this.quotes = quotes;
    this.inventory = inventory;
    this.wallet = wallet;
    this.promotions = promotions;
    this.users = users;
    this.shop = shop;
    this.sales = sales;
    this.holds = holds;
    this.counters = counters;
    this.audit = audit;
    this.json = json;
    this.tx = tx;
    this.clock = clock;
  }

  // ---------- pricing ----------
  /** Prices a basket at current prices and shows discounts, GST and coins. Changes nothing. */
  @Transactional(readOnly = true)
  public BillView quote(Cart cart) {
    Priced priced = price(cart.items());
    String mobile = mobile(cart.customerMobile());
    requireCustomerForPromo(cart, mobile);
    Optional<User> customer = mobile == null ? Optional.empty() : users.findByMobile(mobile);
    UUID userId = customer.map(User::id).orElse(mobile == null ? null : UUID.randomUUID());
    Quote q = quotes.quoteLines(userId, priced.lines(), cart.couponCode(), cart.coinMode(), cart.percent());
    BillCalculator.Bill bill = bill(priced.drafts(), q);
    int balance = customer.map(u -> wallet.balance(u.id())).orElse(0);
    return view(bill, q, mobile == null ? 0 : q.coinsToEarn(), balance);
  }

  /** Invoice numbers of counter bills and credit note numbers of returns, for screens that only hold the id. */
  @Override
  public Map<UUID, String> numbers(java.util.Collection<UUID> ids) { return sales.documentNumbers(ids); }

  /**
   * The GST bill for a quote. The coupon discount goes only on the lines the coupon covers (the quote says which);
   * when the quote carries no per-line split the whole discount is spread over every line.
   */
  private static BillCalculator.Bill bill(List<BillCalculator.Draft> drafts, Quote q) {
    long[] share = q.lines().stream().mapToLong(Quote.Line::couponDiscountPaise).toArray();
    boolean split = share.length == drafts.size() && java.util.Arrays.stream(share).sum() == q.couponDiscountPaise();
    return split ? BillCalculator.build(drafts, share, q.coinDiscountPaise())
        : BillCalculator.build(drafts, q.couponDiscountPaise() + q.coinDiscountPaise());
  }

  private static BillView view(BillCalculator.Bill b, Quote q, long coinsToEarn, int balance) {
    List<BillLine> lines = b.lines().stream().map(l -> new BillLine(l.productId(), l.name(), l.soldBy(), l.qty(),
        l.unitPricePaise(), l.linePaise(), l.discountPaise(), l.netPaise(), l.gstPercent())).toList();
    return new BillView(lines, b.subtotalPaise(), q.couponCode(), q.couponDiscountPaise(), q.coinsUsed(), q.coinDiscountPaise(),
        b.totalPaise(), b.tax().taxable(), b.tax().cgst(), b.tax().sgst(), coinsToEarn, balance, q.warnings());
  }

  private Priced price(List<Item> items) {
    if (items == null || items.isEmpty()) throw ApiException.validation("items", "basket is empty");
    Map<UUID, Integer> merged = new LinkedHashMap<>();
    items.forEach(it -> {
      if (it.productId() == null || it.qty() <= 0) throw ApiException.validation("qty", "quantity must be positive");
      merged.merge(it.productId(), it.qty(), Integer::sum);
    });
    Map<UUID, ProductData> products = catalog.productsById(merged.keySet());
    Map<UUID, ProductPos> attrs = catalog.attributesFor(merged.keySet());
    List<PricedLine> lines = new ArrayList<>();
    List<BillCalculator.Draft> drafts = new ArrayList<>();
    merged.forEach((id, qty) -> {
      ProductData p = products.get(id);
      if (p == null || !p.active()) throw ApiException.validation("productId", "product is not available: " + id);
      ProductPos a = attrs.get(id);
      int max = a.soldBy() == SoldBy.WEIGHT ? MAX_GRAMS : MAX_UNITS;
      if (qty > max) throw ApiException.validation("qty", "quantity too large for " + p.nameEn());
      BillCalculator.Draft d = new BillCalculator.Draft(id, p.nameEn(), a.soldBy(), qty, p.pricePaise(), a.gstPercent(), a.hsn());
      drafts.add(d);
      lines.add(new PricedLine(id, p.categoryId(), p.nameEn(), qty, p.pricePaise(), d.linePaise(), p.coinRedeemable(), p.earnsCoins()));
    });
    return new Priced(lines, drafts);
  }

  private static String mobile(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String m = raw.replaceAll("[\\s-]", "");
    if (m.startsWith("+91")) m = m.substring(3);
    if (!m.matches("[6-9]\\d{9}")) throw ApiException.validation("customerMobile", "enter a 10 digit mobile number");
    return m;
  }

  private static void requireCustomerForPromo(Cart cart, String mobile) {
    boolean promo = (cart.couponCode() != null && !cart.couponCode().isBlank())
        || (cart.coinMode() != null && cart.coinMode() != CoinRedemption.Mode.NONE);
    if (promo && mobile == null) throw ApiException.validation("customerMobile", "customer mobile is needed to use a coupon or coins");
  }

  // ---------- sale ----------
  /** Completes a bill once per idempotency key; a repeated key returns the first bill. */
  public SaleRecord sale(UUID cashierId, String idemKey, SaleInput in) {
    if (idemKey == null || idemKey.isBlank()) throw ApiException.validation("Idempotency-Key", "header is required");
    Optional<SaleRecord> existing = sales.findByIdem(idemKey);
    if (existing.isPresent()) return existing.get();
    try {
      UUID id = tx.execute(s -> doSale(cashierId, idemKey, in));
      return sales.find(id).orElseThrow();
    } catch (DataIntegrityViolationException e) {
      return sales.findByIdem(idemKey).orElseThrow(() -> e);
    }
  }

  private UUID doSale(UUID cashierId, String idemKey, SaleInput in) {
    Cart cart = in.cart();
    Instant now = clock.instant();
    Priced priced = price(cart.items());
    String mobile = mobile(cart.customerMobile());
    requireCustomerForPromo(cart, mobile);
    User customer = mobile == null ? null : users.findByMobile(mobile).orElseGet(() -> users.insertCounterCustomer(mobile, now));
    UUID customerId = customer == null ? null : customer.id();

    Quote q = quotes.quoteLines(customerId, priced.lines(), cart.couponCode(), cart.coinMode(), cart.percent());
    BillCalculator.Bill bill = bill(priced.drafts(), q);
    List<Pay> pays = in.payments() == null ? List.of() : in.payments();
    List<PaymentRules.Line> payLines = pays.stream().map(p -> new PaymentRules.Line(p.mode(), p.amountPaise(), p.reference())).toList();
    long change;
    try {
      change = PaymentRules.change(payLines, bill.totalPaise(), in.cashTenderedPaise());
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("payments", e.getMessage());
    }
    long onCredit = PaymentRules.creditAmount(payLines);
    if (onCredit > 0 && customerId == null) {
      throw ApiException.validation("customerMobile", "customer mobile is needed to sell on credit");
    }

    UUID saleId = UUID.randomUUID();
    int earned = customerId == null ? 0 : (int) q.coinsToEarn();
    inventory.reserve(bill.lines().stream().map(l -> new InventoryService.Item(l.productId(), l.qty())).toList(), saleId, "POS_SALE");
    if (customerId != null) {
      wallet.redeem(customerId, q.coinsUsed(), saleId);
      promotions.consume(q.couponCode(), customerId, saleId, q.couponDiscountPaise());
      wallet.credit(customerId, earned, saleId);
    }

    ShopSetting shopInfo = shop.get();
    String fy = InvoiceNumbers.financialYear(now);
    String invoiceNo = InvoiceNumbers.format(shopInfo.invoicePrefix(), fy, counters.next("INVOICE", fy));
    List<SaleItemRecord> items = bill.lines().stream().map(l -> new SaleItemRecord(UUID.randomUUID(), l.productId(), l.name(),
        l.soldBy(), l.qty(), l.unitPricePaise(), l.linePaise(), l.discountPaise(), l.netPaise(), l.gstPercent(), l.hsn(),
        l.tax().taxable(), l.tax().cgst(), l.tax().sgst(), 0)).toList();
    List<PaymentRecord> payments = pays.stream().map(p -> new PaymentRecord(p.mode(), p.amountPaise(), p.reference())).toList();
    sales.insert(new SaleRecord(saleId, invoiceNo, cashierId, customerId, mobile, bill.subtotalPaise(), q.couponCode(),
        q.couponDiscountPaise(), q.coinsUsed(), q.coinDiscountPaise(), bill.totalPaise(), bill.tax().taxable(), bill.tax().cgst(),
        bill.tax().sgst(), earned, in.cashTenderedPaise(), change, now, items, payments, PaymentRules.classify(payLines)), idemKey);
    if (onCredit > 0) credit.chargeSale(customerId, onCredit, saleId, invoiceNo, cashierId, now);
    audit.log(cashierId, "SALE", "pos_sale", saleId, invoiceNo + " total=" + bill.totalPaise());
    log.info("POS sale invoice={} cashier={} total={} lines={} payments={} onCredit={} coinsUsed={} coinsEarned={}", invoiceNo,
        cashierId, bill.totalPaise(), items.size(), payments.size(), onCredit, q.coinsUsed(), earned);
    return saleId;
  }

  // ---------- read ----------
  public SaleRecord get(UUID id) { return sales.find(id).orElseThrow(() -> ApiException.notFound("Bill")); }

  /** Finds bills by invoice number, customer mobile, or (default) the bills of one day, newest first. */
  public List<SaleRecord> search(String invoiceNo, String mobile, LocalDate date) {
    if (invoiceNo != null && !invoiceNo.isBlank()) return sales.findByInvoiceNo(invoiceNo.strip()).stream().toList();
    if (mobile != null && !mobile.isBlank()) return sales.byMobile(mobile(mobile));
    LocalDate day = date == null ? clock.instant().atZone(InvoiceNumbers.SHOP_ZONE).toLocalDate() : date;
    return sales.between(day.atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant(),
        day.plusDays(1).atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant());
  }

  /** Link for the UPI QR, built from the shop's UPI id. */
  public String upiLink(long amountPaise, String note) {
    ShopSetting s = shop.get();
    if (amountPaise <= 0) throw ApiException.validation("amountPaise", "must be positive");
    if (s.upiId().isBlank()) throw ApiException.unprocessable("UPI_NOT_CONFIGURED", "Shop UPI id is not set", Map.of());
    return UpiLink.build(s.upiId(), s.shopName(), amountPaise, note == null ? "" : note);
  }

  public String receipt(UUID id, int width) {
    SaleRecord s = get(id);
    ShopSetting shopInfo = shop.get();
    String cashier = users.findById(s.cashierId()).map(u -> u.name() != null && !u.name().isBlank() ? u.name() : u.email()).orElse("");
    List<ReceiptLine> lines = s.items().stream().map(i -> new ReceiptLine(i.name(), i.soldBy(), i.qty(), i.unitPricePaise(),
        i.netPaise(), i.gstPercent())).toList();
    List<ReceiptData.PaymentLine> pays = s.payments().stream().map(p -> new ReceiptData.PaymentLine(p.mode(), p.amountPaise())).toList();
    ReceiptData data = new ReceiptData(shopInfo.shopName(), shopInfo.address(), shopInfo.phone(), shopInfo.gstin(), s.invoiceNo(),
        STAMP.format(s.createdAt().atZone(InvoiceNumbers.SHOP_ZONE)), cashier, s.customerMobile() == null ? "" : s.customerMobile(),
        lines, s.subtotalPaise(), s.couponDiscountPaise(), s.coinDiscountPaise(), s.totalPaise(), s.taxablePaise(), s.cgstPaise(),
        s.sgstPaise(), pays, s.changePaise(), s.coinsEarned(), shopInfo.receiptFooter());
    try {
      return ReceiptText.render(data, width);
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("width", e.getMessage());
    }
  }

  // ---------- held baskets ----------
  @Transactional
  public HoldView hold(UUID cashierId, String label, Cart cart) {
    if (cart.items() == null || cart.items().isEmpty()) throw ApiException.validation("items", "basket is empty");
    String mobile = mobile(cart.customerMobile());
    try {
      HoldRecord r = holds.insert(cashierId, label == null ? "" : label.strip(), mobile, json.writeValueAsString(cart), clock.instant());
      return toView(r);
    } catch (JacksonException e) {
      throw new IllegalStateException("could not store held basket", e);
    }
  }

  public List<HoldView> holds() { return holds.all().stream().map(this::toView).toList(); }

  public HoldView getHold(UUID id) { return toView(holds.find(id).orElseThrow(() -> ApiException.notFound("Held basket"))); }

  @Transactional
  public void discardHold(UUID id) {
    if (!holds.delete(id)) throw ApiException.notFound("Held basket");
  }

  private HoldView toView(HoldRecord r) {
    try {
      return new HoldView(r.id(), r.label(), r.customerMobile(), r.cashierId(), r.createdAt(), json.readValue(r.payload(), Cart.class));
    } catch (JacksonException e) {
      throw new IllegalStateException("stored basket is unreadable: " + r.id(), e);
    }
  }
}
