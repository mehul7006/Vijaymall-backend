package com.vijay.mall.pos.app;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.FlashPricing;
import com.vijay.mall.common.ReferenceLookup;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.metrics.Metrics;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.domain.Lot;
import com.vijay.mall.inventory.domain.LotAllocator;
import com.vijay.mall.order.app.QuoteService;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.pos.app.PosViews.BillLine;
import com.vijay.mall.pos.app.PosViews.BillView;
import com.vijay.mall.pos.app.PosViews.Cart;
import com.vijay.mall.pos.app.PosViews.HoldView;
import com.vijay.mall.pos.app.PosViews.Item;
import com.vijay.mall.pos.app.PosViews.ManualDiscount;
import com.vijay.mall.payment.app.MoneyAccountService;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.Rung;
import com.vijay.mall.pos.app.PosViews.RungLine;
import com.vijay.mall.pos.app.PosViews.SaleInput;
import com.vijay.mall.pos.domain.BillCalculator;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.pos.domain.Money;
import com.vijay.mall.pos.domain.OfflineExceptionKind;
import com.vijay.mall.pos.domain.OfflineFinding;
import com.vijay.mall.pos.domain.CreditNoteData;
import com.vijay.mall.pos.domain.PaymentRules;
import com.vijay.mall.pos.domain.PosRecords;
import com.vijay.mall.pos.domain.PosRecords.HoldRecord;
import com.vijay.mall.payment.app.TerminalService;
import com.vijay.mall.payment.domain.CardTerminal.Attachment;
import com.vijay.mall.pos.domain.PosRecords.PaymentRecord;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.ReturnRecord;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Counter billing: price a basket, take payment, keep bills, hold baskets, print receipts. */
@Service
public class PosService implements ReferenceLookup {
  private static final Logger log = LoggerFactory.getLogger(PosService.class);
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
  private static final int MAX_UNITS = 9_999;
  private static final int MAX_GRAMS = 100_000;

  /** A basket priced line by line: the lines for quoting and the drafts for the GST bill. */
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

  /** Creates the service. */
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
  public BillView quote(Cart cart) { return quote(cart, null); }

  /**
   * Same as {@link #quote(Cart)} with the manual discount a sale would carry, so the preview total matches the sale. Only the
   * amount is checked (not negative, not above the bill): the reason and the manager's PIN are enforced when the bill is rung.
   *
   * @throws ApiException 400 for a negative amount or one above the bill
   */
  @Transactional(readOnly = true)
  public BillView quote(Cart cart, ManualDiscount manualDiscount) {
    Priced base = price(cart.items());
    String mobile = mobile(cart.customerMobile());
    requireCustomerForPromo(cart, mobile);
    Optional<User> customer = mobile == null ? Optional.empty() : users.findByMobile(mobile);
    UUID userId = customer.map(User::id).orElse(mobile == null ? null : UUID.randomUUID());
    Priced priced = laned(flashed(base, userId));
    Quote q = quotes.quoteLines(userId, priced.lines(), cart.couponCode(), cart.coinMode(), cart.percent());
    long subtotal = priced.drafts().stream().mapToLong(BillCalculator.Draft::linePaise).sum();
    long manual = previewManual(manualDiscount, subtotal - q.couponDiscountPaise() - q.coinDiscountPaise());
    BillCalculator.Bill bill = bill(priced.drafts(), q, manual);
    int balance = customer.map(u -> wallet.balance(u.id())).orElse(0);
    return view(bill, q, mobile == null ? 0 : q.coinsToEarn(), balance, roundOff(bill.totalPaise()), manual);
  }

  /** The manual discount amount for a preview: no reason or PIN needed, but never negative or more than what is left to pay. */
  private static long previewManual(ManualDiscount md, long remaining) {
    if (md == null || md.amountPaise() == 0) return 0;
    if (md.amountPaise() < 0) throw ApiException.validation("manualDiscount", "must not be negative");
    if (md.amountPaise() > remaining) throw ApiException.validation("manualDiscount", "is more than the bill");
    return md.amountPaise();
  }

  /** Invoice numbers of counter bills and credit note numbers of returns, for screens that only hold the id. */
  @Override
  public Map<UUID, String> numbers(Collection<UUID> ids) { return sales.documentNumbers(ids); }

  /**
   * The GST bill for a quote. The coupon discount goes only on the lines the coupon covers (the quote says which);
   * when the quote carries no per-line split the whole discount is spread over every line.
   */
  private static BillCalculator.Bill bill(List<BillCalculator.Draft> drafts, Quote q) { return bill(drafts, q, 0); }

  /** {@code manual} is the discount typed in at the till; like coins it is spread over what every line still costs. */
  private static BillCalculator.Bill bill(List<BillCalculator.Draft> drafts, Quote q, long manual) {
    long[] share = q.lines().stream().mapToLong(Quote.Line::couponDiscountPaise).toArray();
    boolean split = share.length == drafts.size() && Arrays.stream(share).sum() == q.couponDiscountPaise();
    return split ? BillCalculator.build(drafts, share, q.coinDiscountPaise() + manual)
        : BillCalculator.build(drafts, q.couponDiscountPaise() + q.coinDiscountPaise() + manual);
  }

  /** The round-off mode now in force for counter bills: {@code NEAREST_RUPEE} or {@code NONE}. */
  public String roundOffMode() {
    return settings != null && "NEAREST_RUPEE".equals(settings.get(Settings.GST_ROUND_OFF)) ? "NEAREST_RUPEE" : "NONE";
  }

  /**
   * Rounding of the bill to the nearest rupee, when the admin switched it on (gst.roundOff = NEAREST_RUPEE): the paise that must be
   * added (or taken off, when negative) to reach a whole rupee, half a rupee rounding up. Zero when rounding is off.
   */
  private long roundOff(long totalPaise) {
    if (!"NEAREST_RUPEE".equals(roundOffMode())) return 0;
    return Math.floorDiv(totalPaise + 50, 100) * 100 - totalPaise;
  }

  private static BillView view(BillCalculator.Bill b, Quote q, long coinsToEarn, int balance, long roundOff, long manual) {
    List<BillLine> lines = b.lines().stream().map(l -> new BillLine(l.productId(), l.name(), l.soldBy(), l.qty(),
        l.unitPricePaise(), l.linePaise(), l.discountPaise(), l.netPaise(), l.gstPercent())).toList();
    return new BillView(lines, b.subtotalPaise(), q.couponCode(), q.couponDiscountPaise(), q.coinsUsed(), q.coinDiscountPaise(),
        b.totalPaise() + roundOff, b.tax().taxable(), b.tax().cgst(), b.tax().sgst(), coinsToEarn, balance, q.warnings(), roundOff, manual);
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
      lines.add(new PricedLine(id, p.categoryId(), p.nameEn(), qty, p.pricePaise(), d.linePaise(), p.coinRedeemable(), p.earnsCoins(),
          p.nameEn(), "", null, 0, p.productGroupId()));
    });
    return new Priced(lines, drafts);
  }

  private MoneyAccountService moneyAccounts;

  /** Optional (plain unit tests run without it): names the money account of every payment line. */
  @Autowired(required = false)
  void setMoneyAccounts(MoneyAccountService moneyAccounts) { this.moneyAccounts = moneyAccounts; }

  /**
   * Gives every payment line its money account: the one the till named (checked), else the shop's default for the mode. An offline bill
   * keeps an account that was switched off after the sale was rung.
   */
  private List<Pay> tagged(List<Pay> pays, boolean offline) {
    if (moneyAccounts == null) return pays;
    List<Pay> out = new ArrayList<>();
    for (int i = 0; i < pays.size(); i++) {
      Pay p = pays.get(i);
      if (p.terminalPaymentId() != null) { // the machine's own account is taken when the payment is attached
        out.add(p);
        continue;
      }
      out.add(new Pay(p.mode(), p.amountPaise(), p.reference(), null, moneyAccounts.counterAccount(p.mode(), p.moneyAccountId(),
          "payments[" + i + "].moneyAccountId", offline), p.terminalId()));
    }
    return out;
  }

  private FlashPricing flash;

  /** Optional (plain unit tests run without it): flash sale prices at the counter (items sold by the piece only; returns do not give units back). */
  @Autowired(required = false)
  void setFlash(FlashPricing flash) { this.flash = flash; }

  /** Splits lines that a running flash sale covers: the units that fit go at the sale price, the rest at the normal price. */
  private Priced flashed(Priced p, UUID userId) {
    if (flash == null) return p;
    List<PricedLine> lines = new ArrayList<>();
    List<BillCalculator.Draft> drafts = new ArrayList<>();
    for (int i = 0; i < p.lines().size(); i++) {
      PricedLine l = p.lines().get(i);
      BillCalculator.Draft d = p.drafts().get(i);
      var applied = d.soldBy() == SoldBy.UNIT
          ? flash.apply(l.productId(), FlashPricing.Channel.POS, l.unitPricePaise(), userId, l.qty()) : Optional.<FlashPricing.Applied>empty();
      if (applied.isEmpty()) {
        lines.add(l);
        drafts.add(d);
        continue;
      }
      var a = applied.get();
      lines.add(l.split(a.saleQty(), a.saleUnitPricePaise(), a.flashSaleId(), l.unitPricePaise()));
      drafts.add(new BillCalculator.Draft(d.productId(), d.name(), d.soldBy(), a.saleQty(), a.saleUnitPricePaise(), d.gstPercent(), d.hsn()));
      if (l.qty() > a.saleQty()) {
        lines.add(l.split(l.qty() - a.saleQty(), l.unitPricePaise(), null, 0));
        drafts.add(new BillCalculator.Draft(d.productId(), d.name(), d.soldBy(), l.qty() - a.saleQty(), d.unitPricePaise(), d.gstPercent(), d.hsn()));
      }
    }
    return new Priced(lines, drafts);
  }

  /**
   * Splits the lines of lot-priced products whose stock holds more than one price into runs, each run priced at the lot its units will
   * come from (5 at 100 and 2 at 120 are two lines). Units a flash sale already covers are passed over first, because the flash
   * price overrides the lot price while the sale runs. Products with one price for all their stock are left as they are.
   */
  private Priced laned(Priced p) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (int i = 0; i < p.lines().size(); i++) if (p.drafts().get(i).soldBy() == SoldBy.UNIT) ids.add(p.lines().get(i).productId());
    Map<UUID, List<Lot>> lots = inventory.pricedLots(ids);
    if (lots.isEmpty()) return p;
    Map<UUID, Integer> placed = new LinkedHashMap<>();
    List<PricedLine> lines = new ArrayList<>();
    List<BillCalculator.Draft> drafts = new ArrayList<>();
    for (int i = 0; i < p.lines().size(); i++) {
      PricedLine l = p.lines().get(i);
      BillCalculator.Draft d = p.drafts().get(i);
      int skip = placed.getOrDefault(l.productId(), 0);
      placed.merge(l.productId(), l.qty(), Integer::sum);
      List<LotAllocator.Run> runs = l.flashSaleId() != null || !lots.containsKey(l.productId()) ? List.of()
          : LotAllocator.priceRuns(lots.get(l.productId()), l.qty(), skip);
      boolean same = runs.size() <= 1 && (runs.isEmpty() || runs.getFirst().lot().pricePaise() == null
          || runs.getFirst().lot().pricePaise() == l.unitPricePaise());
      if (same) {
        lines.add(l);
        drafts.add(d);
        continue;
      }
      for (LotAllocator.Run run : runs) {
        long price = run.lot().pricePaise() == null ? l.unitPricePaise() : run.lot().pricePaise();
        lines.add(l.split(run.qty(), price, null, 0));
        drafts.add(new BillCalculator.Draft(d.productId(), d.name(), d.soldBy(), run.qty(), price, d.gstPercent(), d.hsn()));
      }
    }
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

  private ShiftService shifts;
  private boolean requireShift;

  /** Optional: when the shop switches app.pos.require-open-shift on, billing needs an open shift. */
  @Autowired(required = false)
  void setShiftRule(ShiftService shifts,
                    @Value("${app.pos.require-open-shift:false}") boolean require) {
    this.shifts = shifts;
    this.requireShift = require;
  }

  private ApprovalService approvals;
  private int cashierDiscountPercent;
  private RuntimeSettings settings;

  /** Optional (plain unit tests run without it): the admin can change the counter rules from the config screen. */
  @Autowired(required = false)
  void setSettings(RuntimeSettings settings) { this.settings = settings; }

  private int cashierDiscountPercent() {
    return settings != null ? settings.getInt(Settings.POS_CASHIER_DISCOUNT_PERCENT) : cashierDiscountPercent;
  }

  private boolean requireShift() {
    return settings != null ? settings.getBool(Settings.POS_REQUIRE_OPEN_SHIFT) : requireShift;
  }

  /** Optional (plain unit tests run without it): who may approve a manual discount, and how much a cashier may give alone. */
  @Autowired(required = false)
  void setDiscountRule(ApprovalService approvals,
                       @Value("${app.pos.cashier-discount-percent:0}") int percent) {
    this.approvals = approvals;
    this.cashierDiscountPercent = percent;
  }

  /** What the manual discount on a bill turned out to be, once its rules are checked. */
  private record Manual(long amountPaise, String reason, UUID approver) {
    static final Manual NONE = new Manual(0, "", null);
  }

  /**
   * A discount typed in at the till. It needs a reason. An admin may give any amount up to the bill; a cashier may give
   * up to app.pos.cashier-discount-percent of the bill on their own and needs a manager's PIN for more.
   */
  private Manual resolveManual(UUID cashierId, boolean admin, ManualDiscount md, long subtotal, long remaining) {
    if (md == null || md.amountPaise() == 0) return Manual.NONE;
    if (md.amountPaise() < 0) throw ApiException.validation("manualDiscount", "must not be negative");
    String reason = md.reason() == null ? "" : md.reason().strip();
    if (reason.isEmpty() || reason.length() > 100) throw ApiException.validation("manualDiscount", "a reason of up to 100 characters is needed");
    if (md.amountPaise() > remaining) throw ApiException.validation("manualDiscount", "is more than the bill");
    if (admin) return new Manual(md.amountPaise(), reason, cashierId);
    if (cashierDiscountPercent() > 0 && md.amountPaise() * 100 <= subtotal * cashierDiscountPercent()) {
      return new Manual(md.amountPaise(), reason, null);
    }
    if (approvals == null) throw needsApproval("A manager must approve this discount");
    UUID approver = approvals.approverFor(cashierId, md.approverPin())
        .orElseThrow(() -> needsApproval(md.approverPin() == null || md.approverPin().isBlank()
            ? "A manager's approval PIN is needed for this discount" : "That approval PIN is not valid"));
    return new Manual(md.amountPaise(), reason, approver);
  }

  private static ApiException needsApproval(String message) {
    return ApiException.unprocessable("DISCOUNT_NEEDS_APPROVAL", message, Map.of());
  }

  // ---------- sale ----------
  /** Completes a bill once per idempotency key; a repeated key returns the first bill. */
  public SaleRecord sale(UUID cashierId, String idemKey, SaleInput in) { return sale(cashierId, true, idemKey, in); }

  /** {@code admin} says whether the person ringing the bill is a manager, who needs no PIN for a manual discount. */
  public SaleRecord sale(UUID cashierId, boolean admin, String idemKey, SaleInput in) {
    return Metrics.timed(metrics, "mall.pos.sale", "Time to ring up a counter bill",
        () -> saleOnce(cashierId, admin, idemKey, in));
  }

  private MeterRegistry metrics;

  /** Optional (plain unit tests run without it): timers and counters for counter bills. */
  @Autowired(required = false)
  void setMetrics(MeterRegistry metrics) { this.metrics = metrics; }

  private SaleRecord saleOnce(UUID cashierId, boolean admin, String idemKey, SaleInput in) {
    if (idemKey == null || idemKey.isBlank()) throw ApiException.validation("Idempotency-Key", "header is required");
    if (in == null || in.cart() == null) throw ApiException.validation("cart", "is required");
    Optional<SaleRecord> existing = sales.findByIdem(idemKey);
    if (existing.isPresent()) return existing.get();
    // an offline bill was rung up at the till earlier, in that till's own shift: the uploader need not have a shift open now
    if (requireShift() && shifts != null && !in.offline()) shifts.requireOpen(cashierId);
    try {
      UUID id = tx.execute(s -> doSale(cashierId, admin, idemKey, in));
      return sales.find(id).orElseThrow();
    } catch (DataIntegrityViolationException e) {
      return sales.findByIdem(idemKey).orElseThrow(() -> e);
    }
  }

  private UUID doSale(UUID cashierId, boolean admin, String idemKey, SaleInput in) {
    Cart cart = in.cart();
    Instant now = clock.instant();
    Instant soldAt = soldAt(in.soldAt(), now);
    if (in.rung() != null) return doRungSale(cashierId, idemKey, in, now, soldAt);
    Priced base = price(cart.items());
    String mobile = mobile(cart.customerMobile());
    requireCustomerForPromo(cart, mobile);
    User customer = mobile == null ? null : users.findByMobile(mobile).orElseGet(() -> users.insertCounterCustomer(mobile, now));
    UUID customerId = customer == null ? null : customer.id();
    Priced priced = laned(flashed(base, customerId));

    Quote q = quotes.quoteLines(customerId, priced.lines(), cart.couponCode(), cart.coinMode(), cart.percent());
    long subtotal = priced.drafts().stream().mapToLong(BillCalculator.Draft::linePaise).sum();
    Manual manual = resolveManual(cashierId, admin, in.manualDiscount(), subtotal,
        subtotal - q.couponDiscountPaise() - q.coinDiscountPaise());
    BillCalculator.Bill bill = bill(priced.drafts(), q, manual.amountPaise());
    List<Pay> pays = tagged(PayModes.normalize(in.payments(), true, "payments"), in.offline());
    List<PaymentRules.Line> payLines = pays.stream().map(p -> new PaymentRules.Line(p.mode(), p.amountPaise(), p.reference())).toList();
    long roundOff = roundOff(bill.totalPaise());
    long payable = bill.totalPaise() + roundOff;
    long change;
    try {
      change = PaymentRules.change(payLines, payable, in.cashTenderedPaise());
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("payments", e.getMessage());
    }
    long onCredit = PaymentRules.creditAmount(payLines);
    if (onCredit > 0 && customerId == null) {
      throw ApiException.validation("customerMobile", "customer mobile is needed to sell on credit");
    }

    UUID saleId = UUID.randomUUID();
    List<PaymentRecord> paid = withTerminalPayments(pays, saleId, cashierId);
    int earned = customerId == null ? 0 : (int) q.coinsToEarn();
    List<UUID> itemIds = bill.lines().stream().map(_ -> UUID.randomUUID()).toList();
    List<InventoryService.Item> taken = takenItems(bill, itemIds);
    List<OfflineFinding> findings = new ArrayList<>();
    Map<UUID, Long> costs;
    if (in.offline()) {
      OfflineTake t = takeOffline(cashierId, taken, saleId);
      findings.addAll(t.findings());
      costs = t.costs();
    } else {
      costs = inventory.reserve(taken, saleId, "POS_SALE");
    }
    if (customerId != null) {
      wallet.redeem(customerId, q.coinsUsed(), saleId);
      promotions.consume(q.couponCode(), customerId, saleId, q.couponDiscountPaise());
      wallet.credit(customerId, earned, saleId);
    }
    if (flash != null) {
      List<FlashPricing.Claim> claims = priced.lines().stream().filter(l -> l.flashSaleId() != null)
          .map(l -> new FlashPricing.Claim(l.flashSaleId(), l.productId(), l.qty(), l.unitPricePaise(), l.listUnitPricePaise())).toList();
      // an offline bill has already happened at the shelf: it is recorded even if the sale ran out meanwhile
      if (in.offline()) flash.consumeLenient(FlashPricing.Channel.POS, saleId, customerId, claims);
      else flash.consume(FlashPricing.Channel.POS, saleId, customerId, claims);
    }

    ShopSetting shopInfo = shop.get();
    String fy = InvoiceNumbers.financialYear(soldAt); // an offline bill belongs to the year it was sold in, not the day it was uploaded
    String invoiceNo = InvoiceNumbers.format(shopInfo.invoicePrefix(), fy, counters.next("INVOICE", fy));
    long[] couponShares = q.lines().size() == bill.lines().size()
        ? q.lines().stream().mapToLong(Quote.Line::couponDiscountPaise).toArray() : null; // the coupon's part of each line
    List<SaleItemRecord> items = saleItems(bill, itemIds, costs, couponShares);
    List<PaymentRecord> payments = paid;
    sales.insert(new SaleRecord(saleId, invoiceNo, cashierId, customerId, mobile, bill.subtotalPaise(), q.couponCode(),
        q.couponDiscountPaise(), q.coinsUsed(), q.coinDiscountPaise(), payable, bill.tax().taxable(), bill.tax().cgst(),
        bill.tax().sgst(), earned, in.cashTenderedPaise(), change, soldAt, items, payments, PaymentRules.classify(payLines),
        manual.amountPaise(), manual.reason(), manual.approver(), roundOff, q.couponMinOrderPaise()), idemKey);
    if (manual.amountPaise() > 0) {
      audit.log(cashierId, "MANUAL_DISCOUNT", "pos_sale", saleId, invoiceNo + " amount=" + manual.amountPaise() + " reason="
          + manual.reason() + " approvedBy=" + manual.approver());
    }
    flag(saleId, findings);
    if (onCredit > 0) credit.chargeSale(customerId, onCredit, saleId, invoiceNo, cashierId, now);
    audit.log(cashierId, "SALE", "pos_sale", saleId, invoiceNo + " total=" + payable);
    String type = PaymentRules.classify(payLines);
    Metrics.count(metrics, "mall.pos.sales", "Counter bills", "type", type, 1);
    Metrics.count(metrics, "mall.pos.sales.value.paise", "Value of counter bills, in paise", "type", type,
        payable);
    log.info("POS sale invoice={} cashier={} total={} lines={} payments={} onCredit={} coinsUsed={} coinsEarned={}", invoiceNo,
        cashierId, payable, items.size(), payments.size(), onCredit, q.coinsUsed(), earned);
    return saleId;
  }

  private TerminalService terminalPayments;

  /** Optional (plain unit tests run without it): attaches paid card machine payments to the payment lines that name one. */
  @Autowired(required = false)
  void setTerminalPayments(TerminalService terminalPayments) { this.terminalPayments = terminalPayments; }

  /**
   * Attaches the card machine payment of every line that names one, whichever kind of machine took it (it must be paid, unused and of
   * the line's amount; a Paytm payment must also fit the line's mode). The line then takes the machine's money account, terminal id,
   * its reference (the RRN, unless the till sent one) and the masked payer detail. A line that names a different account than the machine's
   * is refused. Lines without a machine payment get their default account as before.
   */
  private List<PaymentRecord> withTerminalPayments(List<Pay> pays, UUID saleId, UUID cashierId) {
    List<PaymentRecord> out = new ArrayList<>();
    for (int i = 0; i < pays.size(); i++) {
      Pay p = pays.get(i);
      if (p.terminalPaymentId() == null) {
        out.add(new PaymentRecord(p.mode(), p.amountPaise(), p.reference(), null, p.moneyAccountId(), p.terminalId()));
        continue;
      }
      String field = "payments[" + i + "].terminalPaymentId";
      if (!"CARD".equals(p.mode()) && !"UPI".equals(p.mode())) throw ApiException.validation(field, "only a CARD or UPI line can use a card machine payment");
      if (terminalPayments == null) throw ApiException.validation(field, "the card machine is not available");
      Attachment c = terminalPayments.attach(p.terminalPaymentId(), saleId, p.amountPaise(), p.mode(), field, cashierId);
      if (p.moneyAccountId() != null && c.moneyAccountId() != null && !p.moneyAccountId().equals(c.moneyAccountId())) {
        throw ApiException.validation("payments[" + i + "].moneyAccountId", "this payment was taken on another card machine than the account named");
      }
      UUID account = c.moneyAccountId() != null ? c.moneyAccountId() : p.moneyAccountId();
      String ref = p.reference() == null || p.reference().isBlank() ? c.reference() : p.reference();
      String terminal = c.terminalId() != null ? c.terminalId() : p.terminalId();
      out.add(new PaymentRecord(p.mode(), p.amountPaise(), ref, p.terminalPaymentId(), account, terminal, c.detail()));
    }
    return out;
  }

  private OfflineExceptionService offlineExceptions;

  /** Optional (plain unit tests run without it): where the differences found in offline bills are kept for a manager. */
  @Autowired(required = false)
  void setOfflineExceptions(OfflineExceptionService offlineExceptions) { this.offlineExceptions = offlineExceptions; }

  /** Stores the differences of a bill that has just been recorded (nothing to do when there are none). */
  private void flag(UUID saleId, List<OfflineFinding> findings) {
    if (findings.isEmpty()) return;
    if (offlineExceptions == null) throw new IllegalStateException("offline exceptions are not available");
    offlineExceptions.record(saleId, findings);
  }

  /**
   * Takes stock for an offline bill that has already left the shop: what is there is taken, never below zero. Whatever
   * the system did not have is logged, audited and returned as OVERSOLD findings so a manager can look into it.
   */
  private OfflineTake takeOffline(UUID cashierId, List<InventoryService.Item> taken, UUID saleId) {
    InventoryService.Taken got = inventory.takeWhatIsLeftCosted(taken, saleId, "POS_SALE");
    List<Map<String, Object>> short_ = got.shortfalls();
    if (short_.isEmpty()) return new OfflineTake(List.of(), got.costs());
    log.warn("Offline bill sold more than the system had in stock; recorded anyway sale={} shortfalls={}", saleId, short_);
    audit.log(cashierId, "OFFLINE_OVERSOLD", "pos_sale", saleId, short_.toString());
    return new OfflineTake(short_.stream().map(s -> new OfflineFinding(OfflineExceptionKind.OVERSOLD,
        "Sold " + s.get("sold") + " but the system had only " + s.get("taken") + " in stock", UUID.fromString((String) s.get("productId")),
        String.valueOf(s.get("taken")), String.valueOf(s.get("sold")))).toList(), got.costs());
  }

  /** What taking an offline bill's stock found: the differences for a manager and what each line cost. */
  private record OfflineTake(List<OfflineFinding> findings, Map<UUID, Long> costs) {}

  private static List<InventoryService.Item> takenItems(BillCalculator.Bill bill, List<UUID> itemIds) {
    List<InventoryService.Item> out = new ArrayList<>();
    for (int i = 0; i < bill.lines().size(); i++) out.add(new InventoryService.Item(bill.lines().get(i).productId(), bill.lines().get(i).qty(), itemIds.get(i)));
    return out;
  }

  /**
   * The stored lines of a bill, each with the stock cost taken from its lots and its part of the coupon discount.
   *
   * @param couponShares each line's part of the coupon discount in paise, or {@code null} when the bill has no coupon
   */
  private static List<SaleItemRecord> saleItems(BillCalculator.Bill bill, List<UUID> itemIds, Map<UUID, Long> costs, long[] couponShares) {
    List<SaleItemRecord> out = new ArrayList<>();
    for (int i = 0; i < bill.lines().size(); i++) {
      var l = bill.lines().get(i);
      out.add(new SaleItemRecord(itemIds.get(i), l.productId(), l.name(), l.soldBy(), l.qty(), l.unitPricePaise(), l.linePaise(), l.discountPaise(),
          l.netPaise(), l.gstPercent(), l.hsn(), l.tax().taxable(), l.tax().cgst(), l.tax().sgst(), 0,
          couponShares == null ? 0 : couponShares[i], costs.get(itemIds.get(i))));
    }
    return out;
  }

  /** Refuses a rung line that cannot be a real sale: missing or non-positive numbers, a GST rate the system does not accept. */
  private static void checkRungLine(RungLine l) {
    if (l == null || l.productId() == null) throw ApiException.validation("rung.lines", "every line needs a productId");
    if (l.qty() == null || l.qty() <= 0) throw ApiException.validation("qty", "quantity must be positive on every line");
    if (l.unitPricePaise() == null || l.unitPricePaise() < 0) throw ApiException.validation("unitPricePaise", "unitPricePaise must be given and not negative");
    if (l.gstPercent() == null || !PosCatalogService.GST_RATES.contains(l.gstPercent())) {
      throw ApiException.validation("gstPercent", "gstPercent must be one of " + PosCatalogService.GST_RATES);
    }
    if (l.discountPaise() != null && l.discountPaise() < 0) throw ApiException.validation("discountPaise", "discountPaise must not be negative");
  }

  /**
   * Records an offline bill exactly as the till rang it: its prices, GST rates and line discounts are taken as given and the tax
   * split and round-off are worked out from them. Coupons, coins and credit cannot be checked offline and are refused. Anything
   * that differs from the catalog today (price, GST rate, product switched off), every discount and every stock shortfall is
   * stored as an exception for a manager; none of them stops the bill.
   */
  private UUID doRungSale(UUID cashierId, String idemKey, SaleInput in, Instant now, Instant soldAt) {
    Cart cart = in.cart();
    boolean coupon = cart.couponCode() != null && !cart.couponCode().isBlank();
    boolean coins = cart.coinMode() != null && cart.coinMode() != CoinRedemption.Mode.NONE;
    if (coupon || coins) {
      throw ApiException.validation("cart", "a coupon or coin redemption cannot be checked offline: ring this bill without it");
    }
    List<Pay> pays = tagged(PayModes.normalize(in.payments(), true, "payments"), true);
    for (int i = 0; i < pays.size(); i++) {
      if (pays.get(i).terminalPaymentId() != null) {
        throw ApiException.validation("payments[" + i + "].terminalPaymentId", "a card machine payment cannot be used on an offline bill");
      }
    }
    List<PaymentRules.Line> payLines = pays.stream().map(p -> new PaymentRules.Line(p.mode(), p.amountPaise(), p.reference())).toList();
    if (PaymentRules.creditAmount(payLines) > 0) {
      throw ApiException.validation("payments", "credit (udhar) cannot be given on an offline bill: it needs the customer's account");
    }
    List<RungLine> rungLines = in.rung().lines();
    if (rungLines == null || rungLines.isEmpty()) throw ApiException.validation("rung.lines", "the bill has no lines");
    rungLines.forEach(PosService::checkRungLine);
    Set<UUID> ids = rungLines.stream().map(RungLine::productId).collect(Collectors.toCollection(LinkedHashSet::new));
    Map<UUID, ProductData> products = catalog.productsById(ids);
    Map<UUID, ProductPos> attrs = catalog.attributesFor(ids);

    List<OfflineFinding> findings = new ArrayList<>();
    List<BillCalculator.Draft> drafts = new ArrayList<>();
    long[] lineDiscounts = new long[rungLines.size()];
    for (int i = 0; i < rungLines.size(); i++) {
      RungLine l = rungLines.get(i);
      ProductData p = products.get(l.productId());
      if (p == null) throw ApiException.validation("productId", "unknown product: " + l.productId());
      ProductPos a = attrs.get(l.productId());
      if (l.qty() > (a.soldBy() == SoldBy.WEIGHT ? MAX_GRAMS : MAX_UNITS)) {
        throw ApiException.validation("qty", "quantity too large for " + p.nameEn());
      }
      String name = l.name() == null || l.name().isBlank() ? p.nameEn() : l.name().strip();
      BillCalculator.Draft d = new BillCalculator.Draft(l.productId(), name, a.soldBy(), l.qty(), l.unitPricePaise(), l.gstPercent(), a.hsn());
      if (l.linePaise() != null && l.linePaise() != d.linePaise()) {
        throw ApiException.validation("linePaise", "linePaise: line amount " + Money.plain(l.linePaise()) + " is not price x quantity (" + Money.plain(d.linePaise()) + ") for " + name);
      }
      lineDiscounts[i] = l.discountPaise() == null ? 0 : l.discountPaise();
      if (lineDiscounts[i] > d.linePaise()) throw ApiException.validation("discountPaise", "discount is more than the line amount of " + name);
      drafts.add(d);
      findings.addAll(differences(p, a, l, name, lineDiscounts[i]));
    }

    long afterLineDiscounts = drafts.stream().mapToLong(BillCalculator.Draft::linePaise).sum() - Arrays.stream(lineDiscounts).sum();
    long manual = in.manualDiscount() == null ? 0 : in.manualDiscount().amountPaise();
    if (manual < 0) throw ApiException.validation("manualDiscount", "manual discount must not be negative");
    if (manual > afterLineDiscounts) throw ApiException.validation("manualDiscount", "manual discount is more than the bill");
    String reason = manual > 0 && in.manualDiscount().reason() != null ? in.manualDiscount().reason().strip() : "";
    if (manual > 0) {
      findings.add(new OfflineFinding(OfflineExceptionKind.DISCOUNT, "Manual discount of Rs " + Money.plain(manual) + " given offline (reason: "
          + (reason.isEmpty() ? "none given" : reason) + "); no manager approved it", null, "0", String.valueOf(manual)));
    }

    BillCalculator.Bill bill = BillCalculator.build(drafts, lineDiscounts, manual);
    long roundOff = roundOff(bill.totalPaise());
    long payable = bill.totalPaise() + roundOff;
    long change;
    try {
      change = PaymentRules.change(payLines, payable, in.cashTenderedPaise());
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("payments", e.getMessage());
    }
    String mobile = mobile(cart.customerMobile());
    User customer = mobile == null ? null : users.findByMobile(mobile).orElseGet(() -> users.insertCounterCustomer(mobile, now));
    UUID customerId = customer == null ? null : customer.id();

    UUID saleId = UUID.randomUUID();
    List<UUID> itemIds = bill.lines().stream().map(_ -> UUID.randomUUID()).toList();
    OfflineTake offlineTake = takeOffline(cashierId, takenItems(bill, itemIds), saleId);
    findings.addAll(offlineTake.findings());

    ShopSetting shopInfo = shop.get();
    String fy = InvoiceNumbers.financialYear(soldAt);
    String invoiceNo = InvoiceNumbers.format(shopInfo.invoicePrefix(), fy, counters.next("INVOICE", fy));
    List<SaleItemRecord> items = saleItems(bill, itemIds, offlineTake.costs(), null); // offline bills carry no coupon
    List<PaymentRecord> payments = pays.stream().map(p -> new PaymentRecord(p.mode(), p.amountPaise(), p.reference(), null, p.moneyAccountId(), p.terminalId())).toList();
    // the subtotal is what the lines cost after the till's own line discounts, so the receipt adds up: subtotal - discount +- round off = total
    sales.insert(new SaleRecord(saleId, invoiceNo, cashierId, customerId, mobile, afterLineDiscounts, null, 0, 0, 0, payable,
        bill.tax().taxable(), bill.tax().cgst(), bill.tax().sgst(), 0, in.cashTenderedPaise(), change, soldAt, items, payments,
        PaymentRules.classify(payLines), manual, reason, null, roundOff), idemKey);
    flag(saleId, findings);
    if (manual > 0) {
      audit.log(cashierId, "MANUAL_DISCOUNT", "pos_sale", saleId, invoiceNo + " amount=" + manual + " reason=" + reason + " approvedBy=none (offline)");
    }
    audit.log(cashierId, "SALE", "pos_sale", saleId, invoiceNo + " total=" + payable + " offline-as-rung");
    String type = PaymentRules.classify(payLines);
    Metrics.count(metrics, "mall.pos.sales", "Counter bills", "type", type, 1);
    Metrics.count(metrics, "mall.pos.sales.value.paise", "Value of counter bills, in paise", "type", type, payable);
    log.info("POS sale invoice={} cashier={} total={} lines={} payments={} mode=as-rung exceptions={}", invoiceNo, cashierId, payable,
        items.size(), payments.size(), findings.size());
    return saleId;
  }

  /** What differs between a rung line and the catalog today: a switched-off product, another price, another GST rate, a discount. */
  private static List<OfflineFinding> differences(ProductData p, ProductPos a, RungLine l, String name, long discount) {
    List<OfflineFinding> out = new ArrayList<>();
    if (!p.active()) {
      out.add(new OfflineFinding(OfflineExceptionKind.PRODUCT_INACTIVE, name + " has been switched off since the till downloaded it",
          p.id(), "active", "inactive"));
    }
    if (l.unitPricePaise() != p.pricePaise()) {
      out.add(new OfflineFinding(OfflineExceptionKind.PRICE_CHANGED, name + " was charged Rs " + Money.plain(l.unitPricePaise())
          + " but the catalog price is Rs " + Money.plain(p.pricePaise()), p.id(), String.valueOf(p.pricePaise()), String.valueOf(l.unitPricePaise())));
    }
    if (l.gstPercent() != a.gstPercent()) {
      out.add(new OfflineFinding(OfflineExceptionKind.GST_CHANGED, name + " was billed at " + l.gstPercent() + "% GST but the catalog rate is "
          + a.gstPercent() + "%", p.id(), String.valueOf(a.gstPercent()), String.valueOf(l.gstPercent())));
    }
    if (discount > 0) {
      out.add(new OfflineFinding(OfflineExceptionKind.DISCOUNT, name + " got a discount of Rs " + Money.plain(discount)
          + " at the till; no manager approved it", p.id(), "0", String.valueOf(discount)));
    }
    return out;
  }

  /** An offline till reports when it really sold: not in the future, and not older than a week. */
  static final long MAX_OFFLINE_DAYS = 7;

  static Instant soldAt(Instant claimed, Instant now) {
    if (claimed == null) return now;
    if (claimed.isAfter(now.plusSeconds(300)) || claimed.isBefore(now.minus(MAX_OFFLINE_DAYS, ChronoUnit.DAYS))) {
      throw ApiException.validation("soldAt", "must be within the last " + MAX_OFFLINE_DAYS + " days and not in the future");
    }
    return claimed;
  }

  /** The bill already stored under this idempotency key, if any (so a re-sent offline bill is recognised, not rung up twice). */
  public Optional<SaleRecord> findByIdempotencyKey(String key) { return sales.findByIdem(key); }

  // ---------- read ----------
  /**
   * One bill with its lines and payments.
   *
   * @throws ApiException 404 if there is no such bill
   */
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

  /**
   * Renders a bill as plain text for a thermal printer.
   *
   * @param width characters per line
   * @throws ApiException 400 if the width is too narrow
   */
  public String receipt(UUID id, int width) {
    SaleRecord s = get(id);
    ShopSetting shopInfo = shop.get();
    String cashier = cashierName(s.cashierId());
    List<ReceiptLine> lines = s.items().stream().map(i -> new ReceiptLine(i.name(), i.soldBy(), i.qty(), i.unitPricePaise(),
        i.netPaise(), i.gstPercent())).toList();
    List<ReceiptData.PaymentLine> pays = s.payments().stream().map(p -> new ReceiptData.PaymentLine(p.mode(), p.amountPaise())).toList();
    ReceiptData data = new ReceiptData(shopInfo.shopName(), shopInfo.address(), shopInfo.phone(), shopInfo.gstin(), s.invoiceNo(),
        STAMP.format(s.createdAt().atZone(InvoiceNumbers.SHOP_ZONE)), cashier, s.customerMobile() == null ? "" : s.customerMobile(),
        lines, s.subtotalPaise(), s.couponDiscountPaise(), s.coinDiscountPaise(), s.totalPaise(), s.taxablePaise(), s.cgstPaise(),
        s.sgstPaise(), pays, s.changePaise(), s.coinsEarned(), shopInfo.receiptFooter(), s.manualDiscountPaise(), s.roundOffPaise());
    try {
      return ReceiptText.render(data, width);
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("width", e.getMessage());
    }
  }

  /**
   * Renders the credit note of a return as plain text for a thermal printer.
   *
   * @param saleId the bill the goods came from
   * @param returnId the return (credit note)
   * @param width characters per line, 32 or 48
   * @throws ApiException 404 for an unknown bill or a return that does not belong to it, 400 for a bad width
   */
  public String creditNoteReceipt(UUID saleId, UUID returnId, int width) {
    SaleRecord s = get(saleId);
    ReturnRecord r = sales.returnsOf(saleId).stream().filter(x -> x.id().equals(returnId)).findFirst()
        .orElseThrow(() -> ApiException.notFound("Credit note"));
    ShopSetting shopInfo = shop.get();
    Map<UUID, SaleItemRecord> byId = s.items().stream().collect(Collectors.toMap(SaleItemRecord::id, Function.identity()));
    List<CreditNoteData.Line> lines = r.items().stream().map(i -> {
      SaleItemRecord sold = byId.get(i.saleItemId());
      return new CreditNoteData.Line(sold.name(), sold.soldBy(), i.qty(), sold.gstPercent(), i.refundPaise());
    }).toList();
    List<ReceiptData.PaymentLine> refunds = r.refunds().stream().map(p -> new ReceiptData.PaymentLine(p.mode(), p.amountPaise())).toList();
    CreditNoteData data = new CreditNoteData(shopInfo.shopName(), shopInfo.address(), shopInfo.phone(), shopInfo.gstin(),
        r.creditNoteNo(), s.invoiceNo(), STAMP.format(r.createdAt().atZone(InvoiceNumbers.SHOP_ZONE)), cashierName(r.cashierId()),
        s.customerMobile() == null ? "" : s.customerMobile(), r.reason() == null ? "" : r.reason(), lines, r.taxablePaise(),
        r.cgstPaise(), r.sgstPaise(), r.refundPaise(), refunds, r.creditAdjustedPaise(), shopInfo.receiptFooter(),
        r.items().stream().mapToLong(PosRecords.ReturnItemRecord::couponSharePaise).sum(), r.couponClawbackPaise());
    try {
      return ReceiptText.renderCreditNote(data, width);
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("width", e.getMessage());
    }
  }

  private String cashierName(UUID id) {
    return users.findById(id).map(u -> u.name() != null && !u.name().isBlank() ? u.name() : u.email()).orElse("");
  }

  // ---------- held baskets ----------
  /** The most lines one parked basket may hold. */
  static final int MAX_HOLD_LINES = 50;

  /**
   * Parks a basket (with an optional label and customer mobile) so the cashier can serve someone else.
   *
   * @throws ApiException 400 if the basket is empty or has more than 50 lines; 409 {@code HOLD_LIMIT} when 100 baskets are already parked (the message says how long baskets are kept)
   */
  @Transactional
  public HoldView hold(UUID cashierId, String label, Cart cart) {
    if (cart.items() == null || cart.items().isEmpty()) throw ApiException.validation("items", "basket is empty");
    if (cart.items().size() > MAX_HOLD_LINES) throw ApiException.validation("items", "must have at most " + MAX_HOLD_LINES + " lines");
    checkHoldQuantities(cart.items());
    if (holds.count() >= PosHoldRepository.MAX_LISTED) {
      String expiry = holdExpiryDays > 0 ? ". Parked baskets are removed automatically after " + holdExpiryDays + (holdExpiryDays == 1 ? " day" : " days") : "";
      throw ApiException.conflict("HOLD_LIMIT", "Too many parked baskets: resume or discard some first" + expiry,
          Map.of("max", PosHoldRepository.MAX_LISTED, "expiryDays", holdExpiryDays));
    }
    String mobile = mobile(cart.customerMobile());
    try {
      HoldRecord r = holds.insert(cashierId, label == null ? "" : label.strip(), mobile, json.writeValueAsString(cart), clock.instant());
      return toView(r);
    } catch (JacksonException e) {
      throw new IllegalStateException("could not store held basket", e);
    }
  }

  /**
   * A parked basket keeps to the same quantity bounds as a bill: 1 to 9999 pieces, or 1 to 100000 grams for a weighed item. Products
   * the till does not know yet are treated as counted items (a hold never checks that products exist).
   */
  private void checkHoldQuantities(List<Item> items) {
    Map<UUID, ProductPos> attrs = catalog.attributesFor(items.stream().map(Item::productId).filter(Objects::nonNull).collect(Collectors.toSet()));
    for (int i = 0; i < items.size(); i++) {
      ProductPos a = attrs.get(items.get(i).productId());
      int max = a != null && a.soldBy() == SoldBy.WEIGHT ? MAX_GRAMS : MAX_UNITS;
      if (items.get(i).qty() < 1 || items.get(i).qty() > max) {
        throw ApiException.validation("cart.items[" + i + "].qty", "must be 1 to " + max);
      }
    }
  }

  private int holdExpiryDays = 7;

  /** Days after which a parked basket is removed automatically ({@code app.pos.hold-expiry-days}, 0 = never). */
  @Autowired(required = false)
  void setHoldExpiryDays(@Value("${app.pos.hold-expiry-days:7}") int days) { this.holdExpiryDays = Math.max(0, days); }

  /**
   * Daily clean-up (03:00 shop time) of baskets parked longer than the expiry, so forgotten ones cannot fill the 100-basket limit. Does
   * nothing when the expiry is 0. Safe to run on several instances at once (the delete is idempotent); an audit entry, a log line and the
   * {@code mall.pos.holds.expired} counter record how many were removed.
   *
   * @return how many baskets were removed
   */
  @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Kolkata")
  public int purgeExpiredHolds() {
    if (holdExpiryDays <= 0) return 0;
    Instant cutoff = clock.instant().minus(holdExpiryDays, ChronoUnit.DAYS);
    int removed = tx.execute(s -> {
      int n = holds.deleteCreatedBefore(cutoff);
      if (n > 0) audit.log(null, "HOLD_EXPIRED", "pos_hold", null, n + " parked basket(s) older than " + holdExpiryDays + " day(s) removed");
      return n;
    });
    if (removed > 0) {
      log.info("Expired parked baskets removed count={} olderThanDays={}", removed, holdExpiryDays);
      Metrics.count(metrics, "mall.pos.holds.expired", "Parked baskets removed after expiry", "reason", "age", removed);
    }
    return removed;
  }

  /** Every parked basket, newest first; at most 100 can exist, so none is ever left out. */
  public List<HoldView> holds() { return holds.all().stream().map(this::toView).toList(); }

  /**
   * One parked basket.
   *
   * @throws ApiException 404 if it was already resumed or discarded
   */
  public HoldView getHold(UUID id) { return toView(holds.find(id).orElseThrow(() -> ApiException.notFound("Held basket"))); }

  /**
   * Deletes a parked basket.
   *
   * @throws ApiException 404 if it does not exist
   */
  @Transactional
  public void discardHold(UUID id) {
    if (!holds.delete(id)) throw ApiException.notFound("Held basket");
  }

  private HoldView toView(HoldRecord r) {
    try {
      return new HoldView(r.id(), r.label(), r.customerMobile(), r.cashierId(), r.createdAt(), json.readValue(r.payload(), Cart.class),
          holdExpiryDays > 0 ? r.createdAt().plus(holdExpiryDays, ChronoUnit.DAYS) : null);
    } catch (JacksonException e) {
      throw new IllegalStateException("stored basket is unreadable: " + r.id(), e);
    }
  }
}
