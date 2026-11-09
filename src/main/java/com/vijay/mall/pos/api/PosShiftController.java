package com.vijay.mall.pos.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.pos.app.ShiftService;
import com.vijay.mall.pos.app.ShiftService.ShiftView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Cash drawer control: cashiers open and close their own shift; the admin reads everyone's. */
@RestController
@RequestMapping("/api/v1")
public class PosShiftController {
  /** Request body for opening a shift: the cash float already in the drawer, in paise. */
  public record OpenBody(@Min(0) long openingFloatPaise) {}

  /**
   * Request body for closing a shift: the cash counted in the drawer, in paise, and a note that is required when it
   * differs from the expected cash.
   */
  public record CloseBody(@Min(0) long countedCashPaise, String note) {}

  private final ShiftService shifts;

  /**
   * Creates the controller.
   *
   * @param shifts shift service
   */
  public PosShiftController(ShiftService shifts) { this.shifts = shifts; }

  /**
   * Opens the calling cashier's shift with the opening float (201). Answers 409 {@code SHIFT_ALREADY_OPEN} if one is
   * already open.
   */
  @PostMapping("/pos/shifts/open")
  @ResponseStatus(HttpStatus.CREATED)
  public ShiftView open(@Valid @RequestBody OpenBody b) { return shifts.open(CurrentUser.id(), b.openingFloatPaise()); }

  /** 404 when the cashier has no open shift. */
  @GetMapping("/pos/shifts/current")
  public ShiftView current() { return shifts.current(CurrentUser.id()); }

  /**
   * Closes the calling cashier's shift with the counted cash. Answers 422 {@code VARIANCE_NEEDS_NOTE} when the count
   * differs from the expected cash and no note is given, and 404 when no shift is open.
   */
  @PostMapping("/pos/shifts/close")
  public ShiftView close(@Valid @RequestBody CloseBody b) { return shifts.close(CurrentUser.id(), b.countedCashPaise(), b.note()); }

  /** Admin only (see SecurityConfig: everything under /admin). */
  @GetMapping("/admin/pos/shifts")
  public Page<ShiftView> list(@RequestParam(required = false) UUID cashierId, @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size) {
    return shifts.list(cashierId, page, size);
  }
}
