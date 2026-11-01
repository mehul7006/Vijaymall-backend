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

@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {
  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) { this.notifications = notifications; }

  @GetMapping
  public NotificationService.Inbox list(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
    return notifications.inbox(CurrentUser.id(), page, size);
  }

  @PostMapping("/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void read(@PathVariable UUID id) { notifications.markRead(CurrentUser.id(), id); }

  @PostMapping("/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void readAll() { notifications.markAllRead(CurrentUser.id()); }
}
