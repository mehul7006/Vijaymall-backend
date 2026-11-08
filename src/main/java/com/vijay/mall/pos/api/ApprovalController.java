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
  public record PinBody(@NotBlank String pin) {}

  private final ApprovalService approvals;

  public ApprovalController(ApprovalService approvals) { this.approvals = approvals; }

  @PutMapping("/approval-pin")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void setPin(@Valid @RequestBody PinBody b) { approvals.setPin(CurrentUser.id(), b.pin()); }
}
