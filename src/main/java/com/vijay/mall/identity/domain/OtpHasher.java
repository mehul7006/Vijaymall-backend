package com.vijay.mall.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Pure helpers: HMAC of an OTP, SHA-256 of a token, constant-time compare. */
public final class OtpHasher {
  private OtpHasher() {}

  /** HMAC-SHA256 of the message keyed with the pepper, as lower-case hex. */
  public static String hmacHex(String pepper, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Hash stored for a one-time code. The key names the channel (the e-mail, or {@code mobile:} plus the number),
   * so a code sent to one channel can never match another.
   * @param pepper the server-side secret
   * @param email the channel key the code belongs to
   * @param code the clear code
   */
  public static String otpHash(String pepper, String email, String code) {
    return hmacHex(pepper, email + ":" + code);
  }

  /** SHA-256 of the value as lower-case hex; used to store refresh tokens without keeping the token itself. */
  public static String sha256Hex(String value) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Compares two strings in constant time so a timing attack cannot reveal how much of a hash matched. */
  public static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
