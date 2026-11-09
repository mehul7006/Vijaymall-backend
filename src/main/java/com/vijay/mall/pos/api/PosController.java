package com.vijay.mall.pos.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.PosReportService;
import com.vijay.mall.pos.app.PosReturnService;
import com.vijay.mall.pos.app.PosService;
import com.vijay.mall.pos.app.PosViews.BillView;
import com.vijay.mall.pos.app.PosViews.Cart;
import com.vijay.mall.pos.app.PosViews.DayReport;
import com.vijay.mall.pos.app.PosViews.HoldView;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.PosProduct;
import com.vijay.mall.pos.app.PosViews.ReturnInput;
import com.vijay.mall.pos.app.PosViews.ReturnLine;
import com.vijay.mall.pos.app.PosViews.SaleInput;
import com.vijay.mall.pos.app.PosViews.Scan;
import com.vijay.mall.pos.app.PosViews;
import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.pos.domain.PosRecords.ReturnRecord;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.WeighedMode;
import com.vijay.mall.wallet.domain.CoinRedemption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Shop counter endpoints. Open to CASHIER and ADMIN (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/pos")
public class PosController {
  /**
   * Request body for a basket: the items, plus optional customer mobile, coupon code and coin redemption choice. The
   * mobile is needed only to use a coupon or coins or to earn coins.
   */
  public record CartBody(@NotEmpty List<PosViews.Item> items, String customerMobile, String couponCode,
                         CoinRedemption.Mode coinMode, Integer percent) {
    Cart toCart() { return new Cart(items, customerMobile, couponCode, coinMode, percent); }
  }

  /**
   * Request body for completing a bill: the basket, how it is paid, the cash handed over (for change) and an optional
   * manual discount.
   */
  public record SaleBody(@NotNull @Valid CartBody cart, List<Pay> payments, Long cashTenderedPaise,
                         PosViews.ManualDiscount manualDiscount) {
    SaleInput toInput() { return new SaleInput(cart.toCart(), payments, cashTenderedPaise, manualDiscount); }
  }

  /**
   * Request body for a price preview: the same basket fields as a cart plus the optional manual discount a sale takes, so
   * the previewed total matches the sale. No reason or PIN is needed to preview.
   */
  public record QuoteBody(@NotEmpty List<PosViews.Item> items, String customerMobile, String couponCode,
                          CoinRedemption.Mode coinMode, Integer percent, PosViews.ManualDiscount manualDiscount) {
    Cart toCart() { return new Cart(items, customerMobile, couponCode, coinMode, percent); }
  }

  /** The shop settings the till needs, plus whether bills are rounded ({@code NONE} or {@code NEAREST_RUPEE}). */
  public record ShopView(String shopName, String address, String phone, String gstin, String stateCode, String upiId,
                         String invoicePrefix, String receiptFooter, String weighedPrefix, WeighedMode weighedMode,
                         String promise, String details, String roundOffMode) {
    static ShopView of(ShopSetting s, String roundOffMode) {
      return new ShopView(s.shopName(), s.address(), s.phone(), s.gstin(), s.stateCode(), s.upiId(), s.invoicePrefix(),
          s.receiptFooter(), s.weighedPrefix(), s.weighedMode(), s.promise(), s.details(), roundOffMode);
    }
  }

  /** Request body for parking a basket: an optional label for the cashier and the basket itself. */
  public record HoldBody(String label, @NotNull @Valid CartBody cart) {}

  /**
   * Request body for a return against a bill: the sold lines and quantities coming back, a reason and how the refund
   * is paid out: either an explicit {@code refunds} split or a {@code refundMode} (CASH, UPI or CARD) for the whole pay-out,
   * never both.
   */
  public record ReturnBody(@NotEmpty List<ReturnLine> items, String reason, List<Pay> refunds, String refundMode) {}

  /** A bill rendered as plain text for a thermal printer, with the character width it was laid out for. */
  public record Receipt(int width, String text) {}

  /** A UPI payment link (for the QR code) for an amount. */
  public record UpiLinkView(String link) {}

  private final PosCatalogService catalog;
  private final PosService pos;
  private final PosReturnService returns;
  private final PosReportService reports;
  private final ShopService shop;

  /**
   * Creates the controller.
   *
   * @param catalog counter product lookup
   * @param pos billing, holds and receipts
   * @param returns item-level returns
   * @param reports day report
   * @param shop shop settings
   */
  public PosController(PosCatalogService catalog, PosService pos, PosReturnService returns, PosReportService reports,
                       ShopService shop) {
    this.catalog = catalog;
    this.pos = pos;
    this.returns = returns;
    this.reports = reports;
    this.shop = shop;
  }

  /**
   * Returns the shop settings the till needs (name, address, GSTIN, UPI id, receipt footer, scale settings) and
   * {@code roundOffMode} ({@code NONE} or {@code NEAREST_RUPEE}, the gst.roundOff setting).
   */
  @GetMapping("/shop")
  public ShopView shop() { return ShopView.of(shop.get(), pos.roundOffMode()); }

  /**
   * Resolves a scanned barcode or weighed-scale label to a product, quantity and line price. Answers 404 for an
   * unknown or inactive product so the cashier can search by name, and 400 for a malformed code.
   */
  @GetMapping("/barcode/{code}")
  public Scan scan(@PathVariable String code) { return catalog.scan(code); }

  /**
   * Searches the products sellable at the counter, page by page. {@code q} matches names, and also a barcode or an item code
   * (PLU) exactly; a product with an exact barcode or code match comes first. {@code updatedSince} (ISO-8601 instant) keeps only
   * products changed at or after it, for an offline till refreshing its copy.
   */
  @GetMapping("/products")
  public Page<PosProduct> products(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince) {
    return catalog.search(q, page, size, updatedSince);
  }

  /**
   * Prices a basket (discounts, GST, coins, round-off) without recording anything. An optional {@code manualDiscount} is
   * subtracted like on a sale (no PIN is needed to preview); a negative amount or one above the bill answers 400.
   */
  @PostMapping("/quote")
  public BillView quote(@Valid @RequestBody QuoteBody body) { return pos.quote(body.toCart(), body.manualDiscount()); }

  /**
   * Completes a bill (201). Requires an {@code Idempotency-Key} header: repeating the same key returns the first bill
   * instead of billing again. Takes stock, applies coins and coupon, books any credit portion and assigns the invoice
   * number. A cashier needs a manager's PIN for a manual discount above their own limit.
   */
  @PostMapping("/sales")
  @ResponseStatus(HttpStatus.CREATED)
  public SaleRecord sale(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody SaleBody body) {
    return pos.sale(CurrentUser.id(), CurrentUser.isAdmin(), key, body.toInput());
  }

  /** Finds bills by invoice number, by customer mobile, or (by default) the bills of one day, newest first. */
  @GetMapping("/sales")
  public List<SaleRecord> sales(@RequestParam(required = false) String invoiceNo, @RequestParam(required = false) String mobile,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return pos.search(invoiceNo, mobile, date);
  }

  /** Returns one bill with its lines, payments and returns so far. Answers 404 if it does not exist. */
  @GetMapping("/sales/{id}")
  public SaleRecord sale(@PathVariable UUID id) { return pos.get(id); }

  /** Returns the bill as printable text for the given paper width in characters. */
  @GetMapping("/sales/{id}/receipt")
  public Receipt receipt(@PathVariable UUID id, @RequestParam(defaultValue = "32") int width) {
    return new Receipt(width, pos.receipt(id, width));
  }

  /** Returns the credit note of a return as printable text for the given paper width (32 or 48). 404 if the return is not on this bill. */
  @GetMapping("/sales/{id}/returns/{returnId}/receipt")
  public Receipt creditNoteReceipt(@PathVariable UUID id, @PathVariable UUID returnId, @RequestParam(defaultValue = "32") int width) {
    return new Receipt(width, pos.creditNoteReceipt(id, returnId, width));
  }

  /**
   * Takes goods back against a bill (201): issues a credit note, puts stock back (or into defective stock), adjusts
   * coins and pays out the refund. Requires an {@code Idempotency-Key} header; a repeat returns the first return.
   * Cashiers may only return recent bills (else 422 {@code RETURN_NEEDS_ADMIN}); asking for more than was sold
   * answers 409 {@code RETURN_EXCEEDS_SOLD}.
   */
  @PostMapping("/sales/{id}/returns")
  @ResponseStatus(HttpStatus.CREATED)
  public ReturnRecord refund(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                             @Valid @RequestBody ReturnBody body) {
    return returns.refund(CurrentUser.id(), CurrentUser.isAdmin(), id, key, new ReturnInput(body.items(), body.reason(), body.refunds(), body.refundMode()));
  }

  /** Lists the returns already made against a bill. */
  @GetMapping("/sales/{id}/returns")
  public List<ReturnRecord> returnsOf(@PathVariable UUID id) { return returns.returnsOf(id); }

  /**
   * Builds the UPI link for a payment of {@code amountPaise}. Answers 422 {@code UPI_NOT_CONFIGURED} if the shop has
   * no UPI id.
   */
  @GetMapping("/upi-link")
  public UpiLinkView upiLink(@RequestParam long amountPaise, @RequestParam(required = false) String note) {
    return new UpiLinkView(pos.upiLink(amountPaise, note));
  }

  /** Parks a basket so the cashier can serve someone else (201). */
  @PostMapping("/holds")
  @ResponseStatus(HttpStatus.CREATED)
  public HoldView hold(@Valid @RequestBody HoldBody body) { return pos.hold(CurrentUser.id(), body.label(), body.cart().toCart()); }

  /** Lists the parked baskets, newest first. */
  @GetMapping("/holds")
  public List<HoldView> holds() { return pos.holds(); }

  /** Returns one parked basket so it can be resumed. Answers 404 if it is gone. */
  @GetMapping("/holds/{id}")
  public HoldView getHold(@PathVariable UUID id) { return pos.getHold(id); }

  /** Deletes a parked basket (204). Answers 404 if it is gone. */
  @DeleteMapping("/holds/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void discardHold(@PathVariable UUID id) { pos.discardHold(id); }

  /** Cash-up. A cashier always sees their own bills; an admin sees everyone's unless cashierId is given. */
  @GetMapping("/reports/day")
  public DayReport day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam(required = false) UUID cashierId) {
    UUID scope = CurrentUser.isAdmin() ? cashierId : CurrentUser.id();
    return reports.day(date, scope);
  }
}
