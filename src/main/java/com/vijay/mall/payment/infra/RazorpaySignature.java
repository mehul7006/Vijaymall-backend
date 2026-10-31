package com.vijay.mall.payment.infra;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 check Razorpay uses for webhooks. Pure, so it is easy to test. */
public final class RazorpaySignature {
  private RazorpaySignature() {}

  public static String sign(String body, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean verify(String body, String signature, String secret) {
    if (signature == null || secret == null || secret.isBlank()) return false;
    return MessageDigest.isEqual(sign(body, secret).getBytes(StandardCharsets.UTF_8),
        signature.trim().getBytes(StandardCharsets.UTF_8));
  }
}
