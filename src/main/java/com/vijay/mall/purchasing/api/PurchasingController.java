package com.vijay.mall.purchasing.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.purchasing.app.PurchaseService;
import com.vijay.mall.purchasing.app.PurchaseService.PurchaseView;
import com.vijay.mall.purchasing.app.StockTakeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Stock coming in and stock counts. Everything under /admin is for the admin only (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin")
public class PurchasingController {
  private com.vijay.mall.common.idem.AdminOps ops;

  /** Makes repeats of an admin action with the same Idempotency-Key harmless (plain unit tests build the controller without it). */
  @org.springframework.beans.factory.annotation.Autowired
  void setOps(com.vijay.mall.common.idem.AdminOps ops) { this.ops = ops; }

  public record PurchaseBody(String supplierName, String invoiceNo, String note, @NotEmpty List<PurchaseService.ItemInput> items) {}

  public record StockTakeBody(String note, @NotEmpty List<StockTakeService.Count> items) {}

  private final PurchaseService purchases;
  private final StockTakeService stockTakes;

  public PurchasingController(PurchaseService purchases, StockTakeService stockTakes) {
    this.purchases = purchases;
    this.stockTakes = stockTakes;
  }

  /** Receive a supplier delivery. Needs an Idempotency-Key so a retried request cannot add the stock twice. */
  @PostMapping("/purchases")
  @ResponseStatus(HttpStatus.CREATED)
  public PurchaseView receive(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PurchaseBody b) {
    return purchases.receive(CurrentUser.id(), key, new PurchaseService.Input(b.supplierName(), b.invoiceNo(), b.note(), b.items()));
  }

  @GetMapping("/purchases")
  public Page<PurchaseView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return purchases.list(page, size);
  }

  @GetMapping("/purchases/{id}")
  public PurchaseView get(@PathVariable UUID id) { return purchases.get(id); }

  /** Sets stock to the counted figures and returns what changed. */
  @PostMapping("/stock-takes")
  public StockTakeService.Result stockTake(@Valid @RequestBody StockTakeBody b, @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String key) {
    // a repeat with the same key returns the first call's result and applies nothing more
    return com.vijay.mall.common.idem.AdminOps.runStored(ops, key, "stock-take", CurrentUser.id(), com.vijay.mall.common.idem.AdminOps.fingerprint(b),
        () -> stockTakes.apply(CurrentUser.id(), b.note(), b.items()), StockTakeService.Result.class);
  }
}
