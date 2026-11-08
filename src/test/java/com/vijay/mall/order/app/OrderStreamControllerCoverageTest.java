package com.vijay.mall.order.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.order.api.OrderStreamController;
import com.vijay.mall.support.TestAuth;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The two stream endpoints register the right kind of stream and switch off proxy buffering. */
class OrderStreamControllerCoverageTest {
  private final OrderEventHub hub = new OrderEventHub(Runnable::run);
  private final OrderStreamController controller = new OrderStreamController(hub);

  @AfterEach
  void tearDown() { TestAuth.clear(); }

  @Test
  void aCustomerStreamIsRegisteredUnderTheSignedInUser() {
    UUID me = UUID.randomUUID();
    TestAuth.login(me, "CUSTOMER");
    MockHttpServletResponse res = new MockHttpServletResponse();
    SseEmitter emitter = controller.mine(res);
    assertThat(emitter).isNotNull();
    assertThat(hub.customerStreams(me)).isEqualTo(1);
    assertThat(hub.staffStreams()).isZero();
    assertThat(res.getHeader("Cache-Control")).isEqualTo("no-cache");
    assertThat(res.getHeader("X-Accel-Buffering")).isEqualTo("no");
  }

  @Test
  void aStaffStreamListensToEveryOrder() {
    MockHttpServletResponse res = new MockHttpServletResponse();
    SseEmitter emitter = controller.all(res);
    assertThat(emitter).isNotNull();
    assertThat(hub.staffStreams()).isEqualTo(1);
    assertThat(res.getHeader("Cache-Control")).isEqualTo("no-cache");
    assertThat(res.getHeader("X-Accel-Buffering")).isEqualTo("no");
  }
}
