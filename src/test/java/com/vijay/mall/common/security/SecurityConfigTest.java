package com.vijay.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.AppProps;
import org.junit.jupiter.api.Test;

class SecurityConfigTest {
  private static AppProps props(String secret) {
    return new AppProps(new AppProps.Jwt(secret, 900, 30), null, null, null, null, null);
  }

  @Test
  void blockedStaffAreRefusedAtOnceButCustomersAreNotLookedUp() {
    var users = org.mockito.Mockito.mock(com.vijay.mall.identity.infra.UserRepository.class);
    var id = java.util.UUID.randomUUID();
    org.mockito.Mockito.when(users.isBlocked(id)).thenReturn(true);
    assertThat(SecurityConfig.isBlockedStaff("CASHIER", id.toString(), users)).isTrue();
    assertThat(SecurityConfig.isBlockedStaff("ADMIN", id.toString(), users)).isTrue();
    assertThat(SecurityConfig.isBlockedStaff("CUSTOMER", id.toString(), users)).isFalse();
    assertThat(SecurityConfig.isBlockedStaff("CASHIER", "not-a-uuid", users)).isFalse();
    org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).isBlocked(org.mockito.ArgumentMatchers.argThat(u -> !u.equals(id)));
  }

  @Test
  void keyFromAThirtyTwoCharacterSecretIsHmacSha256() {
    var key = new SecurityConfig().jwtSecretKey(props("0123456789012345678901234567890a"));
    assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
    assertThat(key.getEncoded()).hasSize(32);
  }

  @Test
  void shortSecretStopsStartup() {
    assertThatThrownBy(() -> new SecurityConfig().jwtSecretKey(props("too-short")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("32");
  }

  @Test
  void encoderAndDecoderShareTheKey() {
    var cfg = new SecurityConfig();
    var key = cfg.jwtSecretKey(props("0123456789012345678901234567890a"));
    var enc = cfg.jwtEncoder(key);
    var dec = cfg.jwtDecoder(key);
    var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().subject("u")
        .issuedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(30)).build();
    String token = enc.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
        org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
        claims)).getTokenValue();
    assertThat(dec.decode(token).getSubject()).isEqualTo("u");
  }
}
