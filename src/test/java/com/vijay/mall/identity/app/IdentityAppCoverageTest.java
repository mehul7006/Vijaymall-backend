package com.vijay.mall.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.ratelimit.RateLimiter;
import com.vijay.mall.identity.domain.OtpHasher;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.OtpRepository;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IdentityAppCoverageTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  // ---- AuthService.roleFor: who becomes admin on first sign-in ----

  private final OtpService otp = mock(OtpService.class);
  private final TokenService tokens = mock(TokenService.class);
  private final UserRepository users = mock(UserRepository.class);

  private String firstLoginRole(String bootstrap) {
    var props = new AppProps(null, null, bootstrap, null, null, null);
    var created = new User(UUID.randomUUID(), "new@b.com", null, null, "X");
    when(users.findByEmail("new@b.com")).thenReturn(Optional.empty());
    var role = ArgumentCaptor.forClass(String.class);
    when(users.insert(eq("new@b.com"), role.capture(), eq(NOW))).thenReturn(created);
    when(tokens.issue(any(), any())).thenReturn(new TokenService.AuthResult("a", "r", 900, created));
    new AuthService(otp, tokens, users, props, clock).verify("new@b.com", "1", "d");
    return role.getValue();
  }

  @Test
  void newAccountIsACustomerWhenNoBootstrapAdminIsConfigured() {
    assertThat(firstLoginRole(null)).isEqualTo(User.CUSTOMER);
  }

  @Test
  void newAccountIsACustomerWhenItIsNotTheBootstrapAdmin() {
    assertThat(firstLoginRole("someone-else@b.com")).isEqualTo(User.CUSTOMER);
  }

  @Test
  void newAccountWithTheBootstrapEmailIgnoringCaseIsAnAdmin() {
    assertThat(firstLoginRole("NEW@B.com")).isEqualTo(User.ADMIN);
  }

  // ---- MobileOtpService.send: where the code comes from ----

  @Test
  void mobileCodeIsRandomWhenNoFixedCodeIsConfigured() {
    var repo = mock(OtpRepository.class);
    var sender = mock(SmsOtpSender.class);
    var props = new AppProps(null, new AppProps.Otp("sms", "pepper", null, "from@x"), null, null, null, null);
    new MobileOtpService(repo, sender, props, clock, mock(RateLimiter.class), 20).send("9876543210", "ip");

    var code = ArgumentCaptor.forClass(String.class);
    verify(sender).send(eq("9876543210"), code.capture());
    assertThat(code.getValue()).matches("\\d{5}");
    verify(repo).insertMobile("9876543210", OtpHasher.otpHash("pepper", "mobile:9876543210", code.getValue()),
        NOW.plus(Duration.ofMinutes(5)), NOW);
  }
}
