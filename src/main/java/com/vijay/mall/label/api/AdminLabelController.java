package com.vijay.mall.label.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.label.app.LabelService;
import com.vijay.mall.label.infra.LabelHistoryRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Barcode label printing; open to admin and counter staff. */
@RestController
@RequestMapping("/api/v1/admin/labels")
public class AdminLabelController {
  /** One variant to print: {@code copies} is a whole number 1 to 500 or the word {@code fromStock}. */
  @Schema(name = "LabelItemBody")
  public record ItemBody(@NotNull UUID variantId, @NotNull Object copies) {}

  /**
   * Print request. {@code startPosition} (sheet templates only) is the first cell to use on the first page. The dates and batch
   * number are printed when the template has those fields.
   */
  public record PrintBody(@NotNull UUID templateId, @NotEmpty @Valid List<ItemBody> items, @NotNull LabelService.Mode mode, Integer startPosition,
                          LocalDate packedOn, LocalDate bestBefore, @Size(max = 40) String batchNo) {}

  private final LabelService labels;

  /** Creates the controller. */
  public AdminLabelController(LabelService labels) { this.labels = labels; }

  /**
   * Prints labels. {@code mode} PDF answers {@code application/pdf}; TSPL and ZPL answer printer commands (send the bytes to the
   * printer as they are); ESCPOS answers binary commands; JSON answers {@code application/json} with the full description. The
   * headers {@code X-Label-Count} and {@code X-Label-Pages} tell how many labels and pages there are. Answers 400 for a bad
   * quantity, more than 2000 labels, a start position outside the sheet, a mode the template cannot use or a label that does not
   * fit, and 404 for an unknown template or variant. A variant without a barcode gets a store-internal one.
   */
  @PostMapping
  public ResponseEntity<?> print(@Valid @RequestBody PrintBody b) {
    List<LabelService.Item> items = b.items().stream().map(i -> new LabelService.Item(i.variantId(), i.copies())).toList();
    LabelService.Printed out = labels.print(new LabelService.Request(b.templateId(), items, b.mode(), b.startPosition(), b.packedOn(), b.bestBefore(),
        b.batchNo()), CurrentUser.id());
    ResponseEntity.BodyBuilder res = ResponseEntity.ok().header("X-Label-Count", Integer.toString(out.labels()))
        .header("X-Label-Pages", Integer.toString(out.pages()))
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + out.mode().filename() + "\"")
        .contentType(MediaType.parseMediaType(out.mode().contentType()));
    return out.json() != null ? res.body(out.json()) : res.body(out.bytes());
  }

  /** The print history, newest first; {@code variantId} narrows it to one variant. */
  @GetMapping("/history")
  public Page<LabelHistoryRepository.Row> history(@RequestParam(required = false) UUID variantId, @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
    return labels.history(variantId, page, size);
  }
}
