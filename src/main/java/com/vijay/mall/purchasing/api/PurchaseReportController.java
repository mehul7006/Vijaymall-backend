package com.vijay.mall.purchasing.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.purchasing.app.PurchaseReportService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Purchase reports. Everything under /admin is for the admin only (see SecurityConfig). Dates are India dates (yyyy-MM-dd). */
@RestController
@RequestMapping("/api/v1/admin/reports/purchases")
public class PurchaseReportController {
  private final PurchaseReportService reports;

  /** Creates the controller. */
  public PurchaseReportController(PurchaseReportService reports) { this.reports = reports; }

  /** The purchase lines in a range (default: this month), filterable by supplier, product and GST rate. */
  @GetMapping("/register")
  public Page<PurchaseReportService.RegisterView> register(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) UUID supplierId,
      @RequestParam(required = false) UUID productId, @RequestParam(required = false) Integer gstRate, @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return reports.register(from, to, supplierId, productId, gstRate, page, size);
  }

  /** Input GST by mode and rate for the range (default: this month), net of debit notes. */
  @GetMapping("/input-gst")
  public PurchaseReportService.InputGst inputGst(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return reports.inputGst(from, to);
  }

  /** Discounts by supplier for the range (default: this month). */
  @GetMapping("/discounts")
  public PurchaseReportService.Discounts discounts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return reports.discounts(from, to);
  }

  /** What each supplier is owed by invoice age: 0-30, 31-60 and 61+ days, as of a day (default today). */
  @GetMapping("/ageing")
  public PurchaseReportService.Ageing ageing(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    return reports.ageing(asOf);
  }
}
