package com.vijay.mall.payment.paytm.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Paytm publishes no test vector, so these tests check the algorithm against an independent re-implementation of the steps
 * described in Paytm's open-source library and prove the round trip. Interoperability with Paytm's servers is UNVERIFIED.
 */
class PaytmChecksumTest {
  private static final String KEY = "0123456789abcdef";
  private static final String BODY = "{\"paytmMid\":\"M1\",\"paytmTid\":\"T1234567\"}";

  @Test
  void roundTripVerifies() {
    String sig = PaytmChecksum.generateSignature(BODY, KEY);
    assertThat(PaytmChecksum.verifySignature(BODY, KEY, sig)).isTrue();
  }

  @Test
  void twoRunsDifferBySaltButBothVerify() {
    java.util.Set<String> sigs = new java.util.HashSet<>();
    for (int i = 0; i < 20; i++) {
      String s = PaytmChecksum.generateSignature(BODY, KEY);
      assertThat(PaytmChecksum.verifySignature(BODY, KEY, s)).isTrue();
      sigs.add(s);
    }
    assertThat(sigs.size()).isGreaterThan(1);
  }

  @Test
  void aFixedSaltGivesTheDocumentedConstruction() throws Exception {
    String salt = "ab#_";
    String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((BODY + "|" + salt).getBytes(StandardCharsets.UTF_8)));
    Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec("@@@@&&&&####$$$$".getBytes(StandardCharsets.UTF_8)));
    String expected = Base64.getEncoder().encodeToString(c.doFinal((hex + salt).getBytes(StandardCharsets.UTF_8)));
    assertThat(PaytmChecksum.signWithSalt(BODY, KEY, salt)).isEqualTo(expected);
    assertThat(PaytmChecksum.verifySignature(BODY, KEY, expected)).isTrue();
  }

  @Test
  void aChangedBodyOrKeyOrJunkFails() {
    String sig = PaytmChecksum.generateSignature(BODY, KEY);
    assertThat(PaytmChecksum.verifySignature(BODY + " ", KEY, sig)).isFalse();
    assertThat(PaytmChecksum.verifySignature(BODY, "fedcba9876543210", sig)).isFalse();
    assertThat(PaytmChecksum.verifySignature(BODY, KEY, "not base64 !!")).isFalse();
    assertThat(PaytmChecksum.verifySignature(BODY, KEY, "")).isFalse();
    assertThat(PaytmChecksum.verifySignature(BODY, KEY, null)).isFalse();
    assertThat(PaytmChecksum.verifySignature(null, KEY, sig)).isFalse();
    assertThat(PaytmChecksum.verifySignature(BODY, null, sig)).isFalse();
    assertThat(PaytmChecksum.verifySignature(BODY, "short", sig)).isFalse();
    String shortPlain = Base64.getEncoder().encodeToString(new byte[16]);
    assertThat(PaytmChecksum.verifySignature(BODY, KEY, shortPlain)).isFalse();
  }
}
