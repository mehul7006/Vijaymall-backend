package com.vijay.mall.order.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.order.app.OrderEventHub;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live order updates as Server-Sent Events. Events: "ready" once connected, "order" for every placed order or status
 * change ({orderId, orderNo, status, previousStatus, at}); comment lines are keep-alives. After an "order" event the
 * app can refresh that order, and after a reconnect it should reload the list once.
 * Other events: "wallet" ({userId, reason, delta, at}) and "credit" ({customerId, reason, deltaPaise, at}) go only to that
 * customer's streams; refetch the coin balance or the credit account. "stock" ({productIds, reason, at}) and "catalog"
 * ({area, at}, area = CATALOG | COUPONS | UNITS | SHOP | PAYMENT_METHODS) go to every stream, at most twice a second
 * (changes in that window are merged); an empty productIds means many products changed, so refetch the lists. On each,
 * refetch that screen's data; nothing needs a re-login.
 * A stream that cannot keep up is closed by the server, and every stream ends after 10 minutes, so on any disconnect
 * reconnect (with a fresh token) and reload the screens once: events are not replayed.
 */
@RestController
public class OrderStreamController {
  private final OrderEventHub hub;

  /** Creates the controller. */
  public OrderStreamController(OrderEventHub hub) { this.hub = hub; }

  /** The signed-in customer's own orders. A browser may pass the token as ?access_token= because EventSource cannot set headers. */
  @GetMapping(value = "/api/v1/orders/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter mine(HttpServletResponse res) {
    noBuffering(res);
    return hub.subscribeCustomer(CurrentUser.id());
  }

  /** Every order, for counter staff and admins (secured in SecurityConfig like the other admin order routes). */
  @GetMapping(value = "/api/v1/admin/orders/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter all(HttpServletResponse res) {
    noBuffering(res);
    return hub.subscribeStaff();
  }

  /** nginx and Cloudflare hold back small chunks unless told not to. */
  private static void noBuffering(HttpServletResponse res) {
    res.setHeader("Cache-Control", "no-cache");
    res.setHeader("X-Accel-Buffering", "no");
  }
}
