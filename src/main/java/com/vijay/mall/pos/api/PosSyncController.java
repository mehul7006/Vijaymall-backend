package com.vijay.mall.pos.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.pos.app.PosSyncService;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints for a counter that works offline and catches up later. Open to CASHIER and ADMIN (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/pos/sync")
public class PosSyncController {
  /** Request body for an offline upload: 1 to 50 bills. */
  public record UploadBody(@NotEmpty List<PosSyncService.OfflineSale> sales) {}

  /** Response of an offline upload: one outcome per bill, in the order sent. */
  public record UploadResult(List<PosSyncService.Result> results) {}

  private final PosSyncService sync;

  /**
   * Creates the controller.
   *
   * @param sync offline sync service
   */
  public PosSyncController(PosSyncService sync) { this.sync = sync; }

  /** The server's clock and the version of the product list; the till downloads again when the version is newer than its copy. */
  @GetMapping("/status")
  public PosSyncService.Status status() { return sync.status(); }

  /** Up to 50 bills rung up offline. Always answers 200 with one outcome per bill; a refused bill does not stop the others. */
  @PostMapping("/sales")
  public UploadResult upload(@Valid @RequestBody UploadBody body) {
    return new UploadResult(sync.upload(CurrentUser.id(), CurrentUser.isAdmin(), body.sales()));
  }
}
