package com.vijay.mall.inventory.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.inventory.app.LotAdminService;
import com.vijay.mall.inventory.app.LotReportService;
import com.vijay.mall.inventory.infra.LotReportRepository.LotRow;
import com.vijay.mall.inventory.infra.LotReportRepository.MarginRow;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stock lots for the admin: lots of a variant, expiry, valuation, margin, the drift check, lot switches and write-off. Admin only (everything under /admin). */
@RestController
public class AdminLotController {
  /** Body of {@code PUT /admin/variants/{id}/lot-settings}. */
  @Schema(name = "LotSettingsBody")
  public record LotSettingsBody(@NotNull Boolean lotPriced, @NotNull Boolean trackExpiry) {}

  /** Body of {@code POST /admin/stock/lots/{id}/write-off}: an optional reason of up to 200 characters. */
  @Schema(name = "LotWriteOffBody")
  public record WriteOffBody(@Size(max = 200) String reason) {}

  private final LotReportService reports;
  private final LotAdminService admin;

  /** Creates the controller. */
  public AdminLotController(LotReportService reports, LotAdminService admin) {
    this.reports = reports;
    this.admin = admin;
  }

  /** The lots of a variant, newest first; {@code onlyOpen=true} keeps those with stock. */
  @GetMapping("/api/v1/admin/variants/{id}/lots")
  public Page<LotRow> lots(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean onlyOpen, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size) {
    return reports.lots(id, onlyOpen, page, size);
  }

  /** Lots with stock expiring within {@code days} days (default: the setting inventory.expiryAlertDays); expired ones are included. At most 200. */
  @GetMapping("/api/v1/admin/stock/expiring")
  public LotReportService.Expiring expiring(@RequestParam(required = false) Integer days) { return reports.expiring(days); }

  /** Stock value at cost by product or category, with the total and the cost-unknown figures. */
  @GetMapping("/api/v1/admin/stock/valuation")
  public LotReportService.Valuation valuation(@RequestParam(defaultValue = "product") String groupBy, @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
    return reports.valuation(groupBy, page, size);
  }

  /** Variants whose lots do not add up to their stock row; the list should be empty. */
  @GetMapping("/api/v1/admin/stock/lot-check")
  public LotReportService.LotCheck lotCheck() { return reports.lotCheck(); }

  /** Margin by product, category or day (India dates, default this month): revenue before tax against lot cost. */
  @GetMapping("/api/v1/admin/reports/margin")
  public Page<MarginRow> margin(@RequestParam(defaultValue = "product") String groupBy,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return reports.margin(groupBy, from, to, page, size);
  }

  /** Switches lot pricing and expiry tracking of a variant. */
  @PutMapping("/api/v1/admin/variants/{id}/lot-settings")
  public Map<String, Object> lotSettings(@PathVariable UUID id, @Valid @RequestBody LotSettingsBody body) {
    return admin.setFlags(CurrentUser.id(), id, body.lotPriced(), body.trackExpiry());
  }

  /** Writes the stock left in a lot off (expired or spoiled). */
  @PostMapping("/api/v1/admin/stock/lots/{id}/write-off")
  public Map<String, Object> writeOff(@PathVariable UUID id, @Valid @RequestBody(required = false) WriteOffBody body) {
    return admin.writeOff(CurrentUser.id(), id, body == null ? null : body.reason());
  }
}
