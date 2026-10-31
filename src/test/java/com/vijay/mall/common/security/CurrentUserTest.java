package com.vijay.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class CurrentUserTest {
  @AfterEach
  void clear() { SecurityContextHolder.clearContext(); }

  private static void signIn(UUID id, String role) {
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(id.toString()).claim("role", role)
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null));
  }

  @Test
  void readsIdAndRoleFromTheJwt() {
    UUID id = UUID.randomUUID();
    signIn(id, "ADMIN");
    assertThat(CurrentUser.id()).isEqualTo(id);
    assertThat(CurrentUser.isAdmin()).isTrue();
  }

  @Test
  void customerIsNotAdmin() {
    signIn(UUID.randomUUID(), "CUSTOMER");
    assertThat(CurrentUser.isAdmin()).isFalse();
  }

  @Test
  void noAuthenticationMeansUnauthenticated() {
    assertThatThrownBy(CurrentUser::id).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("UNAUTHENTICATED"));
  }

  @Test
  void aPrincipalThatIsNotAJwtIsRejected() {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("someone", null));
    assertThatThrownBy(CurrentUser::isAdmin).isInstanceOf(ApiException.class);
  }
}
