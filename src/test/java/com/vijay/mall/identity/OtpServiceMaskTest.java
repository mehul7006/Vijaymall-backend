package com.vijay.mall.identity.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OtpServiceMaskTest {
  @Test
  void masksEverythingButFirstLetterAndDomain() {
    assertEquals("a***@example.com", OtpService.mask("alice@example.com"));
    assertEquals("***", OtpService.mask("a@example.com"));
    assertEquals("***", OtpService.mask("nonsense"));
  }

  @Test
  void normalizeTrimsAndLowercases() {
    assertEquals("a@b.com", OtpService.normalize("  A@B.com "));
  }
}
