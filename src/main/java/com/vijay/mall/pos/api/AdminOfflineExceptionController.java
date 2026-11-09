package com.vijay.mall.pos.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.pos.app.OfflineExceptionService;
import com.vijay.mall.pos.infra.OfflineExceptionRepository.Row;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The manager's list of differences found in bills a till rang up offline. Admin only (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/pos/offline-exceptions")
public class AdminOfflineExceptionController {
  /** note is optional, up to 500 characters. */
  public record ReviewBody(String note) {}

  private final OfflineExceptionService exceptions;

  /**
   * Creates the controller.
   *
   * @param exceptions service holding the offline-bill exceptions
   */
  public AdminOfflineExceptionController(OfflineExceptionService exceptions) { this.exceptions = exceptions; }

  /** status OPEN (default) or REVIEWED; newest first. */
  @GetMapping
  public Page<Row> list(@RequestParam(defaultValue = "OPEN") String status, @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size) {
    return exceptions.list(status, page, size);
  }

  /** Marks one exception as looked at. A second review of the same exception answers 409 ALREADY_REVIEWED. */
  @PostMapping("/{id}/review")
  public Row review(@PathVariable UUID id, @Valid @RequestBody ReviewBody body) {
    return exceptions.review(id, body.note(), CurrentUser.id());
  }
}
