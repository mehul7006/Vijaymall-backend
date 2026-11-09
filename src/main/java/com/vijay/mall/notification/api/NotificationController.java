package com.vijay.mall.notification.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.notification.app.NotificationService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's notification inbox under {@code /api/v1/me/notifications}. Any signed-in user may call it.
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {
  private final NotificationService notifications;

  /** Creates the controller (wired by Spring). */
  public NotificationController(NotificationService notifications) { this.notifications = notifications; }

  /**
   * Returns a page of notifications, newest first, plus the unread count. {@code page} starts at 0; the default size is 20.
   */
  @GetMapping
  public NotificationService.Inbox list(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
    return notifications.inbox(CurrentUser.id(), page, size);
  }

  /** Marks one notification read (204). Fails with 404 when it is not the caller's. */
  @PostMapping("/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void read(@PathVariable UUID id) { notifications.markRead(CurrentUser.id(), id); }

  /** Marks all of the caller's notifications read (204). */
  @PostMapping("/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void readAll() { notifications.markAllRead(CurrentUser.id()); }
}
