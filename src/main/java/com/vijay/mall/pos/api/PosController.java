package com.vijay.mall.pos.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.PosReportService;
import com.vijay.mall.pos.app.PosReturnService;
import com.vijay.mall.pos.app.PosService;
import com.vijay.mall.pos.app.PosViews;
import com.vijay.mall.pos.app.PosViews.BillView;
import com.vijay.mall.pos.app.PosViews.Cart;
import com.vijay.mall.pos.app.PosViews.DayReport;
import com.vijay.mall.pos.app.PosViews.HoldView;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.PosProduct;
import com.vijay.mall.pos.app.PosViews.ReturnInput;
import com.vijay.mall.pos.app.PosViews.ReturnLine;
import com.vijay.mall.pos.app.PosViews.Scan;
import com.vijay.mall.pos.app.PosViews.SaleInput;
import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.pos.domain.PosRecords.ReturnRecord;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
  public record CartBody(@NotEmpty List<PosViews.Item> items, String customerMobile, String couponCode,
                         com.vijay.mall.wallet.domain.CoinRedemption.Mode coinMode, Integer percent) {
    Cart toCart() { return new Cart(items, customerMobile, couponCode, coinMode, percent); }
  }

  public record SaleBody(@NotNull @Valid CartBody cart, List<Pay> payments, Long cashTenderedPaise,
                         PosViews.ManualDiscount manualDiscount) {
    SaleInput toInput() { return new SaleInput(cart.toCart(), payments, cashTenderedPaise, manualDiscount); }
  }

  public record HoldBody(String label, @NotNull @Valid CartBody cart) {}

  public record ReturnBody(@NotEmpty List<ReturnLine> items, String reason, List<Pay> refunds) {}

  public record Receipt(int width, String text) {}

  public record UpiLinkView(String link) {}

  private final PosCatalogService catalog;
  private final PosService pos;
  private final PosReturnService returns;
  private final PosReportService reports;
  private final ShopService shop;

  public PosController(PosCatalogService catalog, PosService pos, PosReturnService returns, PosReportService reports,
                       ShopService shop) {
    this.catalog = catalog;
    this.pos = pos;
    this.returns = returns;
    this.reports = reports;
    this.shop = shop;
  }

  @GetMapping("/shop")
  public ShopSetting shop() { return shop.get(); }

  @GetMapping("/barcode/{code}")
  public Scan scan(@PathVariable String code) { return catalog.scan(code); }

  @GetMapping("/products")
  public Page<PosProduct> products(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
    return catalog.search(q, page, size);
  }

  @PostMapping("/quote")
  public BillView quote(@Valid @RequestBody CartBody body) { return pos.quote(body.toCart()); }

  @PostMapping("/sales")
  @ResponseStatus(HttpStatus.CREATED)
  public SaleRecord sale(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody SaleBody body) {
    return pos.sale(CurrentUser.id(), CurrentUser.isAdmin(), key, body.toInput());
  }

  @GetMapping("/sales")
  public List<SaleRecord> sales(@RequestParam(required = false) String invoiceNo, @RequestParam(required = false) String mobile,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return pos.search(invoiceNo, mobile, date);
  }

  @GetMapping("/sales/{id}")
  public SaleRecord sale(@PathVariable UUID id) { return pos.get(id); }

  @GetMapping("/sales/{id}/receipt")
  public Receipt receipt(@PathVariable UUID id, @RequestParam(defaultValue = "32") int width) {
    return new Receipt(width, pos.receipt(id, width));
  }

  @PostMapping("/sales/{id}/returns")
  @ResponseStatus(HttpStatus.CREATED)
  public ReturnRecord refund(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                             @Valid @RequestBody ReturnBody body) {
    return returns.refund(CurrentUser.id(), CurrentUser.isAdmin(), id, key, new ReturnInput(body.items(), body.reason(), body.refunds()));
  }

  @GetMapping("/sales/{id}/returns")
  public List<ReturnRecord> returnsOf(@PathVariable UUID id) { return returns.returnsOf(id); }

  @GetMapping("/upi-link")
  public UpiLinkView upiLink(@RequestParam long amountPaise, @RequestParam(required = false) String note) {
    return new UpiLinkView(pos.upiLink(amountPaise, note));
  }

  @PostMapping("/holds")
  @ResponseStatus(HttpStatus.CREATED)
  public HoldView hold(@Valid @RequestBody HoldBody body) { return pos.hold(CurrentUser.id(), body.label(), body.cart().toCart()); }

  @GetMapping("/holds")
  public List<HoldView> holds() { return pos.holds(); }

  @GetMapping("/holds/{id}")
  public HoldView getHold(@PathVariable UUID id) { return pos.getHold(id); }

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
