package com.vijay.mall.pos.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.pos.app.ApprovalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** An admin sets the PIN they type at the till to approve a cashier's manual discount. Admin only (everything under /admin). */
@RestController
@RequestMapping("/api/v1/admin/me")
public class ApprovalController {
  /** Request body carrying the new approval PIN (4 to 8 digits). */
  public record PinBody(@NotBlank String pin) {}

  private final ApprovalService approvals;

  /**
   * Creates the controller.
   *
   * @param approvals manager PIN service
   */
  public ApprovalController(ApprovalService approvals) { this.approvals = approvals; }

  /** Sets or replaces the calling admin's approval PIN (204). Answers 400 unless the PIN is 4 to 8 digits. */
  @PutMapping("/approval-pin")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void setPin(@Valid @RequestBody PinBody b) { approvals.setPin(CurrentUser.id(), b.pin()); }
}
