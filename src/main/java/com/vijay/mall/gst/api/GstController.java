package com.vijay.mall.gst.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.gst.app.GstService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads of the GST and HSN master, for the admin and the cashier. The role rule is not an annotation: {@code SecurityConfig} lets
 * only ADMIN and CASHIER call {@code GET /api/v1/gst/**}. {@code /gst/hsn}, {@code /gst/hsn/{hsn}} and {@code /gst/rates} carry an
 * ETag ({@code EtagConfig}); {@code /gst/suggest} does not.
 */
@RestController
@RequestMapping("/api/v1/gst")
public class GstController {
  private final GstService gst;

  /** Creates the controller. */
  public GstController(GstService gst) { this.gst = gst; }

  /**
   * Best HSN and GST rate for a product name, with the other conditions of the same HSN ({@code choices}) and other likely HSN
   * codes ({@code alternatives}). {@code best} is null when nothing scores 0.5 or more.
   */
  @GetMapping("/suggest")
  public GstService.Suggestion suggest(@RequestParam String name, @RequestParam(required = false) String category,
                                       @RequestParam(required = false) BigDecimal price, @RequestParam(required = false) Boolean packed,
                                       @RequestParam(required = false) Integer limit) {
    return gst.suggest(name, category, price, packed, limit);
  }

  /** Searches or lists the HSN rate rows, 20 per page by default (1 to 50). */
  @GetMapping("/hsn")
  public Page<GstService.RowView> list(@RequestParam(required = false) String q, @RequestParam(required = false) String verification,
                                       @RequestParam(required = false) String taxType, @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
    return gst.list(q, verification, taxType, page, size);
  }

  /** All rows of one HSN code (its conditions and rates); 404 {@code NOT_FOUND} when the code is unknown. */
  @GetMapping("/hsn/{hsn}")
  public GstService.HsnRows byHsn(@PathVariable String hsn) { return gst.byHsn(hsn); }

  /** The rate card with whether billing can store each rate today. */
  @GetMapping("/rates")
  public GstService.RateList rates() { return gst.rates(); }
}
