package com.vijay.mall.report.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.report.app.CreditReportService;
import com.vijay.mall.report.app.ReportService;
import com.vijay.mall.report.app.ReportSummaryService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner reports (ADMIN only, everything under {@code /admin}): the dashboard, the sales summary for a period, the credit (udhar) report and the list of credit accounts.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminReportController {
  private final ReportService reports;
  private final ReportSummaryService summaries;
  private final CreditReportService creditReports;

  /** Creates the controller. */
  public AdminReportController(ReportService reports, ReportSummaryService summaries, CreditReportService creditReports) {
    this.reports = reports;
    this.summaries = summaries;
    this.creditReports = creditReports;
  }

  /**
   * Today's sales and orders, new orders waiting, coins issued, the last seven days and the products low on stock; kept for 30 seconds.
   */
  @GetMapping("/dashboard")
  public ReportService.Dashboard dashboard() { return reports.dashboard(); }

  /** period = week | month | quarter (default week), or an explicit from/to as yyyy-MM-dd, both days included. */
  @GetMapping("/reports/summary")
  public ReportSummaryService.Summary summary(@RequestParam(required = false) String period,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return summaries.summary(period, from, to);
  }

  /** Credit given, recovered and outstanding for a period (same period / from / to rules as the sales summary). */
  @GetMapping("/reports/credit")
  public CreditReportService.Report credit(@RequestParam(required = false) String period,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return creditReports.report(period, from, to);
  }

  /** Customers with credit accounts, biggest balance first. */
  @GetMapping("/credit/accounts")
  public Page<CreditReportService.Debtor> creditAccounts(
      @RequestParam(defaultValue = "true") boolean outstandingOnly, @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return creditReports.debtors(outstandingOnly, page, size);
  }
}
