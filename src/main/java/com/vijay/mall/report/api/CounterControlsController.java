package com.vijay.mall.report.api;

import com.vijay.mall.report.app.CounterControlsService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin only (everything under /admin): who discounts, refunds and comes up short at the counter. */
@RestController
@RequestMapping("/api/v1/admin")
public class CounterControlsController {
  private final CounterControlsService controls;

  /** Creates the controller. */
  public CounterControlsController(CounterControlsService controls) { this.controls = controls; }

  /** period = week | month | quarter (default week), or an explicit from/to as yyyy-MM-dd, both days included. */
  @GetMapping("/reports/counter-controls")
  public CounterControlsService.Report report(@RequestParam(required = false) String period,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return controls.report(period, from, to);
  }
}
