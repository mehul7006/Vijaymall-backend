package com.vijay.mall.report.api;

import com.vijay.mall.report.app.ReportService;
import com.vijay.mall.report.app.ReportSummaryService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminReportController {
  private final ReportService reports;
  private final ReportSummaryService summaries;

  public AdminReportController(ReportService reports, ReportSummaryService summaries) {
    this.reports = reports;
    this.summaries = summaries;
  }

  @GetMapping("/dashboard")
  public ReportService.Dashboard dashboard() { return reports.dashboard(); }

  /** period = week | month | quarter (default week), or an explicit from/to as yyyy-MM-dd, both days included. */
  @GetMapping("/reports/summary")
  public ReportSummaryService.Summary summary(@RequestParam(required = false) String period,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return summaries.summary(period, from, to);
  }
}
