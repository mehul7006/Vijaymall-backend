package com.vijay.mall.flash.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.flash.app.FlashReportService;
import com.vijay.mall.flash.app.FlashSaleService;
import com.vijay.mall.flash.app.FlashViews;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Staff (admin and cashier) configure flash sales and read their reports. */
@RestController
@RequestMapping("/api/v1/admin/flash-sales")
public class AdminFlashController {
  private final FlashSaleService sales;
  private final FlashReportService reports;

  /** Creates the controller with the sale service and the report service. */
  public AdminFlashController(FlashSaleService sales, FlashReportService reports) {
    this.sales = sales;
    this.reports = reports;
  }

  /**
   * Creates a flash sale with its items; answers 201 Created. Open to ADMIN and CASHIER.
   * <p>Errors: validation errors for a bad name, window, discount or item, and 409 {@code FLASH_OVERLAP} when a product is
   * already in another running or scheduled sale at that time.
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public FlashViews.SaleView create(@RequestBody FlashSaleService.CreateInput in) { return sales.create(in, CurrentUser.id()); }

  /** status = SCHEDULED | ACTIVE | ENDED | CANCELLED (omit for all). */
  @GetMapping
  public Page<FlashViews.SaleSummary> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
    return sales.list(status, page, size);
  }

  /** Every sale that started in the range, with totals and the best items. from/to are days (yyyy-MM-dd, both included). */
  @GetMapping("/report")
  public FlashReportService.RangeReport range(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return reports.range(from, to);
  }

  /** Returns one sale with its items and their current sale prices; 404 when unknown. */
  @GetMapping("/{id}")
  public FlashViews.SaleView get(@PathVariable UUID id) { return sales.get(id); }

  /** Sales figures of one sale: units, revenue, discount given, split by item, channel and hour. */
  @GetMapping("/{id}/report")
  public FlashReportService.SaleReport report(@PathVariable UUID id) { return reports.report(id); }

  /**
   * Changes the fields that are sent (the others stay). A running sale can only be renamed, re-described or have its end moved,
   * otherwise 409 {@code FLASH_RUNNING}; an ended or cancelled sale cannot be changed ({@code FLASH_NOT_ALLOWED}).
   */
  @PatchMapping("/{id}")
  public FlashViews.SaleView update(@PathVariable UUID id, @RequestBody FlashSaleService.UpdateInput in) {
    return sales.update(id, in, CurrentUser.id());
  }

  /**
   * Adds a product to a sale that has not ended; answers 201 Created. 409 {@code FLASH_OVERLAP} when the product is in another sale then.
   */
  @PostMapping("/{id}/items")
  @ResponseStatus(HttpStatus.CREATED)
  public FlashViews.SaleView addItem(@PathVariable UUID id, @RequestBody FlashSaleService.ItemInput in) {
    return sales.addItem(id, in, CurrentUser.id());
  }

  /**
   * Replaces an item's discount, cap and customer limit. Once units have sold, only the cap may change, and only upwards ({@code FLASH_ITEM_SOLD}).
   */
  @PutMapping("/{id}/items/{itemId}")
  public FlashViews.SaleView updateItem(@PathVariable UUID id, @PathVariable UUID itemId, @RequestBody FlashSaleService.ItemInput in) {
    return sales.updateItem(id, itemId, in, CurrentUser.id());
  }

  /** Removes an item that has never sold a unit; 409 {@code FLASH_ITEM_SOLD} otherwise. */
  @DeleteMapping("/{id}/items/{itemId}")
  public FlashViews.SaleView removeItem(@PathVariable UUID id, @PathVariable UUID itemId) {
    return sales.removeItem(id, itemId, CurrentUser.id());
  }

  /** Starts a scheduled sale immediately; 409 {@code FLASH_NOT_ALLOWED} for a sale in any other state. */
  @PostMapping("/{id}/start-now")
  public FlashViews.SaleView startNow(@PathVariable UUID id) { return sales.startNow(id, CurrentUser.id()); }

  /** Ends a running sale immediately; 409 {@code FLASH_NOT_ALLOWED} for a sale that is not running. */
  @PostMapping("/{id}/end-now")
  public FlashViews.SaleView endNow(@PathVariable UUID id) { return sales.endNow(id, CurrentUser.id()); }

  /** Cancels a scheduled or running sale. Units already sold keep their price. */
  @PostMapping("/{id}/cancel")
  public FlashViews.SaleView cancel(@PathVariable UUID id) { return sales.cancel(id, CurrentUser.id()); }

  /**
   * Copies a sale and its items onto a new schedule (body optional: new name, start and end); answers 201 Created. Sold counts start at zero.
   */
  @PostMapping("/{id}/duplicate")
  @ResponseStatus(HttpStatus.CREATED)
  public FlashViews.SaleView duplicate(@PathVariable UUID id, @RequestBody(required = false) FlashSaleService.DuplicateInput in) {
    return sales.duplicate(id, in, CurrentUser.id());
  }
}
