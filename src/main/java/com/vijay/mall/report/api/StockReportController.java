package com.vijay.mall.report.api;

import com.vijay.mall.report.app.ReportService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin only (everything under /admin): what to reorder. */
@RestController
@RequestMapping("/api/v1/admin/stock")
public class StockReportController {
  private final ReportService reports;

  public StockReportController(ReportService reports) { this.reports = reports; }

  /** Products at or below their own stock alert level, emptiest first. limit is 1 to 200 (default 50). */
  @GetMapping("/low")
  public List<ReportService.LowItem> low(@RequestParam(defaultValue = "50") int limit) { return reports.lowStock(limit); }
}
