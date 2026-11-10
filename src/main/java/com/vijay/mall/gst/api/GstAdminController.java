package com.vijay.mall.gst.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.gst.app.GstAdminService;
import com.vijay.mall.gst.app.GstRowInput;
import com.vijay.mall.gst.app.GstService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's edits of the GST master. Admin only: {@code SecurityConfig} already restricts {@code /api/v1/admin/**} to the ADMIN
 * role, so a cashier gets 403. No Idempotency-Key: a repeated PUT or DELETE gives the same result, and a repeated POST at worst adds
 * a duplicate row that can be switched off. Every field is checked in the service so one 400 names all the wrong fields.
 */
@RestController
@RequestMapping("/api/v1/admin/gst-master/rows")
public class GstAdminController {
  /**
   * Request body to create or change a row. {@code gstPercent} is the total (IGST) rate, for example 5 or 0.25; CGST and SGST are half
   * each. {@code keywords} are item names ADDED under this HSN, never removed by an edit. On update a missing optional field keeps the
   * stored value.
   */
  @Schema(name = "GstRowBody")
  public record RowBody(String hsn, String description, String nameGu, BigDecimal gstPercent, BigDecimal cessRate, String taxType,
                        String condition, String conditionLabel, String verification, String notificationReference,
                        LocalDate effectiveFrom, String note, List<String> keywords) {
    GstRowInput toInput() {
      return new GstRowInput(hsn, description, nameGu, gstPercent, cessRate, taxType, condition, conditionLabel, verification,
          notificationReference, effectiveFrom, note, keywords);
    }
  }

  private final GstAdminService admin;

  /** Creates the controller. */
  public GstAdminController(GstAdminService admin) { this.admin = admin; }

  /** Adds a row: 201 with the stored row. 400 names every wrong field. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public GstService.RowView create(@RequestBody RowBody b) { return admin.create(b.toInput(), CurrentUser.id()); }

  /** Changes a row (and switches it back on if it was off); the next suggestion on this server uses the new rate. 404 for an unknown id. */
  @PutMapping("/{rowId}")
  public GstService.RowView update(@PathVariable UUID rowId, @RequestBody RowBody b) {
    return admin.update(rowId, b.toInput(), CurrentUser.id());
  }

  /** Switches a row off (204, also when it is off already). 409 {@code GST_HSN_IN_USE} when item names would be left without an active row. */
  @DeleteMapping("/{rowId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deactivate(@PathVariable UUID rowId) { admin.deactivate(rowId, CurrentUser.id()); }
}
