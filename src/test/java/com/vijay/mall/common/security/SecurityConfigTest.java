package com.vijay.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

class SecurityConfigTest {
  private static AppProps props(String secret) {
    return new AppProps(new AppProps.Jwt(secret, 900, 30), null, null, null, null, null);
  }

  @Test
  void blockedStaffAreRefusedAtOnceButCustomersAreNotLookedUp() {
    var users = Mockito.mock(UserRepository.class);
    var id = UUID.randomUUID();
    Mockito.when(users.isBlocked(id)).thenReturn(true);
    assertThat(SecurityConfig.isBlockedStaff("CASHIER", id.toString(), users)).isTrue();
    assertThat(SecurityConfig.isBlockedStaff("ADMIN", id.toString(), users)).isTrue();
    assertThat(SecurityConfig.isBlockedStaff("CUSTOMER", id.toString(), users)).isFalse();
    assertThat(SecurityConfig.isBlockedStaff("CASHIER", "not-a-uuid", users)).isFalse();
    Mockito.verify(users, Mockito.never()).isBlocked(ArgumentMatchers.argThat(u -> !u.equals(id)));
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
    var claims = JwtClaimsSet.builder().subject("u")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(30)).build();
    String token = enc.encode(JwtEncoderParameters.from(
        JwsHeader.with(MacAlgorithm.HS256).build(),
        claims)).getTokenValue();
    assertThat(dec.decode(token).getSubject()).isEqualTo("u");
  }
}
