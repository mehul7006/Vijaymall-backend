package com.vijay.mall.payment.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.payment.app.BankReportService;
import com.vijay.mall.payment.infra.BankReportRepository.FeeRow;
import com.vijay.mall.payment.infra.BankReportRepository.PayerRow;
import com.vijay.mall.payment.infra.BankReportRepository.ReceiptLine;
import com.vijay.mall.payment.infra.BankReportRepository.ReceiptTotal;
import com.vijay.mall.payment.infra.BankReportRepository.ReceiptRow;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin only (everything under /admin): receipts by account, fees, and how an order was paid. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminBankReportController {
  private final BankReportService reports;

  /** Creates the controller. */
  public AdminBankReportController(BankReportService reports) { this.reports = reports; }

  /**
   * Money received and paid back per account and method (UPI, CARD, CASH, or the online method). Both days are included, India time; default the
   * last 30 days, at most 400 days. Money without an account is the bucket with moneyAccountId null and accountName "Unassigned".
   */
  @GetMapping("/bank-reports/receipts")
  public Page<ReceiptRow> receipts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return reports.receipts(from, to, page, size);
  }

  /**
   * All receipts of all vendors in one normalized list (online orders and counter sales), newest first. Filters: provider, account, method
   * (UPI, CARD, CASH, NETBANKING, WALLET...). Payer detail is masked.
   */
  @GetMapping("/receipts")
  public Page<ReceiptLine> receiptLines(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String provider,
      @RequestParam(required = false) UUID account, @RequestParam(required = false) String method, @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return reports.receiptLines(from, to, provider, account, method, page, size);
  }

  /** Totals per vendor, account and method for the same range. */
  @GetMapping("/receipts/summary")
  public List<ReceiptTotal> receiptTotals(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return reports.receiptTotals(from, to);
  }

  /** Gateway fee and GST on the fee per month and account (the GST is input credit). */
  @GetMapping("/bank-reports/fees")
  public Page<FeeRow> fees(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return reports.fees(from, to, page, size);
  }

  /** How the order's online payments were made: UPI id, card network and last four digits, bank, fee and settlement. Never a full card number. */
  @GetMapping("/payments/by-order/{orderId}")
  public List<PayerRow> payers(@PathVariable UUID orderId) { return reports.payers(orderId); }
}
