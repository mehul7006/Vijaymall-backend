package com.vijay.mall.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthServiceTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");

  private final OtpService otp = mock(OtpService.class);
  private final TokenService tokens = mock(TokenService.class);
  private final UserRepository users = mock(UserRepository.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private AuthService service(String bootstrapAdmin) {
    var props = new AppProps(null, null, bootstrapAdmin, null, null, null);
    return new AuthService(otp, tokens, users, props, clock);
  }

  private static User user(String email, String role) { return new User(UUID.randomUUID(), email, null, null, role); }

  private TokenService.AuthResult result(User u) { return new TokenService.AuthResult("a", "r", 900, u); }

  @Test
  void existingCustomerGetsTokens() {
    User u = user("a@b.com", User.CUSTOMER);
    when(users.findByEmail("a@b.com")).thenReturn(Optional.of(u));
    when(tokens.issue(u, "dev")).thenReturn(result(u));
    var out = service(null).verify("A@b.com", "12345", "dev");
    assertThat(out.user()).isEqualTo(u);
    var order = inOrder(otp, tokens);
    order.verify(otp).verify("A@b.com", "12345");
    order.verify(tokens).issue(u, "dev");
    verify(users, never()).insert(any(), any(), any());
  }

  @Test
  void firstLoginCreatesACustomer() {
    User u = user("new@b.com", User.CUSTOMER);
    when(users.findByEmail("new@b.com")).thenReturn(Optional.empty());
    when(users.insert("new@b.com", User.CUSTOMER, NOW)).thenReturn(u);
    when(tokens.issue(u, null)).thenReturn(result(u));
    assertThat(service("").verify("new@b.com", "1", null).user().role()).isEqualTo(User.CUSTOMER);
  }

  @Test
  void bootstrapAdminEmailIsCreatedAsAdmin() {
    User u = user("boss@b.com", User.ADMIN);
    when(users.findByEmail("boss@b.com")).thenReturn(Optional.empty());
    when(users.insert("boss@b.com", User.ADMIN, NOW)).thenReturn(u);
    when(tokens.issue(u, "d")).thenReturn(result(u));
    assertThat(service(" BOSS@b.com ").verify("boss@b.com", "1", "d").user().role()).isEqualTo(User.ADMIN);
    verify(users, never()).setRole(any(), any());
  }

  @Test
  void existingCustomerWhoMatchesBootstrapEmailIsPromoted() {
    User u = user("boss@b.com", User.CUSTOMER);
    when(users.findByEmail("boss@b.com")).thenReturn(Optional.of(u));
    when(tokens.issue(any(), any())).thenAnswer(i -> result(i.getArgument(0)));
    var out = service("boss@b.com").verify("boss@b.com", "1", "d");
    verify(users).setRole(u.id(), User.ADMIN);
    assertThat(out.user().role()).isEqualTo(User.ADMIN);
    assertThat(out.user().id()).isEqualTo(u.id());
  }

  @Test
  void existingAdminIsNotPromotedAgain() {
    User u = user("boss@b.com", User.ADMIN);
    when(users.findByEmail("boss@b.com")).thenReturn(Optional.of(u));
    when(tokens.issue(u, "d")).thenReturn(result(u));
    service("boss@b.com").verify("boss@b.com", "1", "d");
    verify(users, never()).setRole(any(), any());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void blankBootstrapEmailNeverPromotes(String boot) {
    User u = user("a@b.com", User.CUSTOMER);
    when(users.findByEmail("a@b.com")).thenReturn(Optional.of(u));
    when(tokens.issue(u, "d")).thenReturn(result(u));
    service(boot).verify("a@b.com", "1", "d");
    verify(users, never()).setRole(any(), any());
  }

  @Test
  void otherEmailIsNotPromotedWhenBootstrapIsSet() {
    User u = user("a@b.com", User.CUSTOMER);
    when(users.findByEmail("a@b.com")).thenReturn(Optional.of(u));
    when(tokens.issue(u, "d")).thenReturn(result(u));
    service("boss@b.com").verify("a@b.com", "1", "d");
    verify(users, never()).setRole(any(), any());
  }

  @Test
  void blockedUserIsRefusedAndGetsNoTokens() {
    User u = user("a@b.com", User.CUSTOMER);
    when(users.findByEmail("a@b.com")).thenReturn(Optional.of(u));
    when(users.isBlocked(u.id())).thenReturn(true);
    assertThatThrownBy(() -> service(null).verify("a@b.com", "1", "d"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
    verify(tokens, never()).issue(any(), any());
  }

  @Test
  void badOtpStopsEverything() {
    org.mockito.Mockito.doThrow(ApiException.unprocessable("OTP_INVALID", "no", java.util.Map.of()))
        .when(otp).verify("a@b.com", "0");
    assertThatThrownBy(() -> service(null).verify("a@b.com", "0", "d")).isInstanceOf(ApiException.class);
    verify(users, never()).findByEmail(any());
  }
}
