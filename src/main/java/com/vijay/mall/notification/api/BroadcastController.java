package com.vijay.mall.notification.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.notification.app.Audience;
import com.vijay.mall.notification.app.BroadcastService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin announcements under {@code /api/v1/admin/notifications}. Admin only (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/notifications")
public class BroadcastController {
  private final BroadcastService broadcasts;
  private AdminOps ops;

  /** Makes a repeat with the same Idempotency-Key return the same broadcast (plain unit tests build the controller without it). */
  @Autowired(required = false)
  void setOps(AdminOps ops) { this.ops = ops; }

  /** Creates the controller (wired by Spring). */
  public BroadcastController(BroadcastService broadcasts) { this.broadcasts = broadcasts; }

  /** Queues a broadcast and answers 202 at once; it is sent in the background. Send an optional {@code Idempotency-Key}. */
  @PostMapping("/broadcast")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public BroadcastService.View broadcast(@RequestBody BroadcastService.Request body,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return AdminOps.run(ops, key, "notification-broadcast", CurrentUser.id(), AdminOps.fingerprint(body),
        () -> broadcasts.enqueue(CurrentUser.id(), body), v -> v.id().toString(), id -> broadcasts.get(UUID.fromString(id)));
  }

  /** How many people a role audience reaches right now, and how many of them have a push device. */
  @GetMapping("/audience-size")
  public BroadcastService.Size audienceSize(@RequestParam Audience audience) { return broadcasts.size(audience); }

  /** Broadcasts, newest first. */
  @GetMapping("/broadcasts")
  public Page<BroadcastService.View> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return broadcasts.list(page, size);
  }

  /** One broadcast with its progress; 404 when unknown. */
  @GetMapping("/broadcasts/{id}")
  public BroadcastService.View get(@PathVariable UUID id) { return broadcasts.get(id); }
}
