package com.vijay.mall.payment.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.payment.app.BankReportService;
import com.vijay.mall.payment.app.SettlementService;
import com.vijay.mall.payment.infra.BankReportRepository.SettlementItemRow;
import com.vijay.mall.payment.infra.BankReportRepository.SettlementRow;
import com.vijay.mall.payment.infra.SettlementRepository.ExceptionRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Admin only (everything under /admin): settlements, statement imports, unsettled money and the exception queue. */
@RestController
@RequestMapping("/api/v1/admin/settlements")
public class AdminSettlementController {
  private final BankReportService reports;
  private final SettlementService settlements;

  /** Creates the controller. */
  public AdminSettlementController(BankReportService reports, SettlementService settlements) {
    this.reports = reports;
    this.settlements = settlements;
  }

  /** Request body of a note on a problem. */
  @Schema(name = "SettlementResolveBody")
  public record ResolveBody(String note) {}

  /** Request body of a manual sync; {@code days} defaults to 7 (1 to 60). */
  public record SyncBody(String provider, Integer days) {}

  /** Settlements paid out in a range (both days included; default the last 30 days), newest first. */
  @GetMapping
  public Page<SettlementRow> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(required = false) UUID accountId, @RequestParam(required = false) String provider,
                                  @RequestParam(required = false) String source, @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
    return reports.settlements(from, to, accountId, provider, source, page, size);
  }

  /** Money received and not yet settled, oldest first, with the expected day and an overdue flag. */
  @GetMapping("/unsettled")
  public Page<BankReportService.Unsettled> unsettled(@RequestParam(defaultValue = "false") boolean overdueOnly, @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
    return reports.unsettled(overdueOnly, page, size);
  }

  /** The problem queue, newest first. status is OPEN or RESOLVED; kind is OVERDUE_UNSETTLED, UNMATCHED_ITEM or AMOUNT_MISMATCH. */
  @GetMapping("/exceptions")
  public Page<ExceptionRow> exceptions(@RequestParam(required = false) String status, @RequestParam(required = false) String kind,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return settlements.exceptions(status, kind, page, size);
  }

  /** Closes a problem with a note. Closing a closed one changes nothing. */
  @PostMapping("/exceptions/{id}/resolve")
  public ExceptionRow resolve(@PathVariable UUID id, @RequestBody ResolveBody b) { return settlements.resolve(CurrentUser.id(), id, b.note()); }

  /** Pulls a provider's recent settlements now (the nightly job does the same). */
  @PostMapping("/sync")
  public SettlementService.SyncResult sync(@RequestBody SyncBody b) {
    int days = b.days() == null ? SettlementService.LOOKBACK_DAYS : b.days();
    if (days < 1 || days > 60) throw ApiException.validation("days", "1 to 60");
    return settlements.sync(b.provider(), days);
  }

  /**
   * Imports an acquirer or bank statement CSV (multipart). Column names default to date, reference, amount, fee, tax, description; send
   * dateColumn, referenceColumn, amountColumn, feeColumn, taxColumn, descriptionColumn to map another file. {@code format=PAYTM} reads the Paytm
   * dashboard Settlement Report (also picked automatically by its header; {@code GENERIC} forces the column mapping). Importing the same file again
   * changes nothing.
   */
  @PostMapping("/import")
  public SettlementService.ImportResult importCsv(@RequestPart("file") MultipartFile file, @RequestParam UUID moneyAccountId,
                                                  @RequestParam(required = false) String provider, @RequestParam(required = false) String utr,
                                                  @RequestParam(required = false) String dateColumn, @RequestParam(required = false) String referenceColumn,
                                                  @RequestParam(required = false) String amountColumn, @RequestParam(required = false) String feeColumn,
                                                  @RequestParam(required = false) String taxColumn, @RequestParam(required = false) String descriptionColumn,
                                                  @RequestParam(required = false) String dateFormat, @RequestParam(required = false) String amountUnit,
                                                  @RequestParam(required = false) String delimiter,
                                                  @RequestParam(required = false) String format) throws IOException {
    return settlements.importCsv(CurrentUser.id(), file.getBytes(), provider, moneyAccountId, utr, new SettlementService.CsvMapping(dateColumn,
        referenceColumn, amountColumn, feeColumn, taxColumn, descriptionColumn, dateFormat, amountUnit, delimiter), format);
  }

  /** One settlement. */
  @GetMapping("/{id}")
  public SettlementRow get(@PathVariable UUID id) { return reports.settlement(id); }

  /** The lines inside a settlement; unmatchedOnly=true shows just the ones that need a person. */
  @GetMapping("/{id}/items")
  public Page<SettlementItemRow> items(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean unmatchedOnly,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return reports.settlementItems(id, unmatchedOnly, page, size);
  }
}
