package com.vijay.mall.payment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vijay.mall.payment.infra.RazorpaySignature;
import org.junit.jupiter.api.Test;

class RazorpaySignatureTest {
  @Test
  void acceptsAMatchingSignature() {
    String body = "{\"event\":\"payment.captured\"}";
    assertTrue(RazorpaySignature.verify(body, RazorpaySignature.sign(body, "s3cret"), "s3cret"));
  }

  @Test
  void rejectsATamperedBody() {
    String sig = RazorpaySignature.sign("{\"a\":1}", "s3cret");
    assertFalse(RazorpaySignature.verify("{\"a\":2}", sig, "s3cret"));
  }

  @Test
  void rejectsWrongSecretMissingSignatureOrEmptySecret() {
    String sig = RazorpaySignature.sign("x", "one");
    assertFalse(RazorpaySignature.verify("x", sig, "two"));
    assertFalse(RazorpaySignature.verify("x", null, "one"));
    assertFalse(RazorpaySignature.verify("x", sig, ""));
  }
}
