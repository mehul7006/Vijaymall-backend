package com.vijay.mall.report.api;

import com.vijay.mall.report.app.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminReportController {
  private final ReportService reports;

  public AdminReportController(ReportService reports) { this.reports = reports; }

  @GetMapping("/dashboard")
  public ReportService.Dashboard dashboard() { return reports.dashboard(); }
}
