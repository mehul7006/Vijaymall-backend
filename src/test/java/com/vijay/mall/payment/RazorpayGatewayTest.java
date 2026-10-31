package com.vijay.mall.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import tools.jackson.databind.json.JsonMapper;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.payment.domain.InvalidSignatureException;
import com.vijay.mall.payment.domain.PaymentEvent;
import com.vijay.mall.payment.infra.RazorpayGateway;
import com.vijay.mall.payment.infra.RazorpaySignature;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RazorpayGatewayTest {
  private static final String SECRET = "whsec_test";
  private final RazorpayGateway gateway = new RazorpayGateway(
      new AppProps(null, null, null, null, new AppProps.Payment("razorpay", 15, "rzp_test_key", "secret", SECRET, "http://localhost:1"),
          new AppProps.Delivery(0, 0)), JsonMapper.builder().build());

  private Map<String, String> signed(String body, String eventId) {
    return Map.of("x-razorpay-signature", RazorpaySignature.sign(body, SECRET), "x-razorpay-event-id", eventId);
  }

  private static String event(String type, String status) {
    return "{\"event\":\"" + type + "\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_9\",\"order_id\":\"order_7\","
        + "\"amount\":125000,\"status\":\"" + status + "\",\"error_description\":\"card declined\"}}}}";
  }

  @Test
  void capturedEventBecomesCaptured() {
    String body = event("payment.captured", "captured");
    PaymentEvent ev = gateway.parseWebhook(body, signed(body, "evt_1"));
    PaymentEvent.Captured c = assertInstanceOf(PaymentEvent.Captured.class, ev);
    assertEquals("evt_1", c.eventId());
    assertEquals("order_7", c.providerOrderId());
    assertEquals("pay_9", c.providerRef());
    assertEquals(125000L, c.amountPaise());
  }

  @Test
  void failedEventBecomesFailedWithReason() {
    String body = event("payment.failed", "failed");
    PaymentEvent.Failed f = assertInstanceOf(PaymentEvent.Failed.class, gateway.parseWebhook(body, signed(body, "evt_2")));
    assertEquals("card declined", f.reason());
  }

  @Test
  void otherEventsAreIgnored() {
    String body = event("refund.created", "x");
    assertInstanceOf(PaymentEvent.Ignored.class, gateway.parseWebhook(body, signed(body, "evt_3")));
  }

  @Test
  void wrongSignatureOrMissingHeaderIsRejected() {
    String body = event("payment.captured", "captured");
    assertThrows(InvalidSignatureException.class, () -> gateway.parseWebhook(body, Map.of("x-razorpay-signature", "deadbeef")));
    assertThrows(InvalidSignatureException.class, () -> gateway.parseWebhook(body, Map.of()));
  }

  @Test
  void tamperedBodyIsRejected() {
    String body = event("payment.captured", "captured");
    Map<String, String> headers = signed(body, "evt_4");
    assertThrows(InvalidSignatureException.class, () -> gateway.parseWebhook(body.replace("125000", "100"), headers));
  }

  @Test
  void sessionCarriesWhatTheAppNeeds() {
    var s = gateway.session("order_7", 125000);
    assertEquals("RAZORPAY", s.type());
    assertEquals("rzp_test_key", s.payload().get("keyId"));
    assertEquals("order_7", s.payload().get("razorpayOrderId"));
    assertEquals(125000L, s.payload().get("amountPaise"));
    assertEquals("INR", s.payload().get("currency"));
  }

  @Test
  void eventIdFallsBackWhenHeaderIsMissing() {
    String body = event("payment.captured", "captured");
    PaymentEvent ev = gateway.parseWebhook(body, Map.of("x-razorpay-signature", RazorpaySignature.sign(body, SECRET)));
    assertEquals("payment.captured:pay_9", ev.eventId());
  }
}
