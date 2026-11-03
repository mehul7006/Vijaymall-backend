package com.vijay.mall.order.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.order.app.ReorderService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders/{id}/reorder")
public class ReorderController {
  private final ReorderService reorders;

  public ReorderController(ReorderService reorders) { this.reorders = reorders; }

  /** What of this earlier order can be ordered again right now, line by line, with the reason for any that cannot. */
  @GetMapping
  public ReorderService.Reorder check(@PathVariable UUID id) { return reorders.check(CurrentUser.id(), id); }
}
