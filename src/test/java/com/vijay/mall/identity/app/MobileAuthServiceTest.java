package com.vijay.mall.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MobileAuthServiceTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
  private static final String MOBILE = "9876543210";

  private final MobileOtpService otp = mock(MobileOtpService.class);
  private final TokenService tokens = mock(TokenService.class);
  private final UserRepository users = mock(UserRepository.class);
  private final MobileAuthService service =
      new MobileAuthService(otp, tokens, users, Clock.fixed(NOW, ZoneOffset.UTC));

  private static User user() { return new User(UUID.randomUUID(), null, null, MOBILE, User.CUSTOMER); }

  private TokenService.AuthResult result(User u) { return new TokenService.AuthResult("a", "r", 900, u); }

  @Test
  void accountThatAlreadyProvedTheNumberIsUsed() {
    User u = user();
    when(users.findByVerifiedMobile(MOBILE)).thenReturn(Optional.of(u));
    when(tokens.issue(u, "dev")).thenReturn(result(u));
    assertThat(service.verify("+91 98765 43210", "12345", "dev").user()).isEqualTo(u);
    var order = inOrder(otp, tokens);
    order.verify(otp).verify(MOBILE, "12345");
    order.verify(tokens).issue(u, "dev");
    verify(users, never()).insertMobileCustomer(any(), any());
    verify(users, never()).adoptCounterCustomer(any());
  }

  @Test
  void aCounterCustomerWithThatNumberIsClaimed() {
    User u = user();
    when(users.findByVerifiedMobile(MOBILE)).thenReturn(Optional.empty());
    when(users.adoptCounterCustomer(MOBILE)).thenReturn(Optional.of(u));
    when(tokens.issue(u, null)).thenReturn(result(u));
    assertThat(service.verify(MOBILE, "1", null).user()).isEqualTo(u);
    verify(users, never()).insertMobileCustomer(any(), any());
  }

  @Test
  void aBrandNewNumberCreatesACustomer() {
    User u = user();
    when(users.findByVerifiedMobile(MOBILE)).thenReturn(Optional.empty());
    when(users.adoptCounterCustomer(MOBILE)).thenReturn(Optional.empty());
    when(users.insertMobileCustomer(MOBILE, NOW)).thenReturn(u);
    when(tokens.issue(u, "d")).thenReturn(result(u));
    assertThat(service.verify(MOBILE, "1", "d").user().role()).isEqualTo(User.CUSTOMER);
  }

  @Test
  void blockedUserGetsNoTokens() {
    User u = user();
    when(users.findByVerifiedMobile(MOBILE)).thenReturn(Optional.of(u));
    when(users.isBlocked(u.id())).thenReturn(true);
    assertThatThrownBy(() -> service.verify(MOBILE, "1", "d")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
    verify(tokens, never()).issue(any(), any());
  }

  @Test
  void aBadCodeStopsBeforeAnyAccountIsTouched() {
    Mockito.doThrow(ApiException.unprocessable("OTP_INVALID", "bad", Map.of()))
        .when(otp).verify(MOBILE, "0");
    assertThatThrownBy(() -> service.verify(MOBILE, "0", "d")).isInstanceOf(ApiException.class);
    verify(users, never()).findByVerifiedMobile(any());
  }
}
