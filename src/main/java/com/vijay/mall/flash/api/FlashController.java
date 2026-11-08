package com.vijay.mall.flash.api;

import com.vijay.mall.flash.app.FlashSaleService;
import com.vijay.mall.flash.app.FlashViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public: the running and coming flash sales (no sign-in needed to browse). */
@RestController
@RequestMapping("/api/v1/flash-sales")
public class FlashController {
  private final FlashSaleService sales;

  public FlashController(FlashSaleService sales) { this.sales = sales; }

  @GetMapping("/active")
  public FlashViews.ShopSales active() { return sales.active(); }

  @GetMapping("/upcoming")
  public FlashViews.ShopSales upcoming() { return sales.upcoming(); }
}
