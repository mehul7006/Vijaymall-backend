package com.vijay.mall.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vijay.mall.identity.domain.OtpHasher;
import org.junit.jupiter.api.Test;

class OtpHasherTest {
  @Test
  void sameInputGivesSameHash() {
    assertEquals(OtpHasher.otpHash("pepper", "a@b.com", "12345"), OtpHasher.otpHash("pepper", "a@b.com", "12345"));
  }

  @Test
  void hashDependsOnPepperEmailAndCode() {
    String base = OtpHasher.otpHash("pepper", "a@b.com", "12345");
    assertNotEquals(base, OtpHasher.otpHash("other", "a@b.com", "12345"));
    assertNotEquals(base, OtpHasher.otpHash("pepper", "c@d.com", "12345"));
    assertNotEquals(base, OtpHasher.otpHash("pepper", "a@b.com", "12346"));
  }

  @Test
  void sha256IsStableAndHex() {
    assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", OtpHasher.sha256Hex("hello"));
  }

  @Test
  void constantTimeEquals() {
    assertTrue(OtpHasher.constantTimeEquals("abc", "abc"));
    assertFalse(OtpHasher.constantTimeEquals("abc", "abd"));
    assertFalse(OtpHasher.constantTimeEquals("abc", "abcd"));
  }
}
