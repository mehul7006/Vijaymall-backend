package com.vijay.mall.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.OtpHasher;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.RefreshTokenRepository;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Uses a real HS256 encoder/decoder so the token contents are checked, not mocked. */
class TokenServiceTest {
  private static final Instant NOW = Instant.now();
  private static final SecretKey KEY = new SecretKeySpec("0123456789-0123456789-0123456789-ab".getBytes(), "HmacSHA256");

  private final RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final TokenService service = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(KEY)),
      new AppProps(new AppProps.Jwt("s", 900, 30), null, null, null, null, null), clock, refresh, users);
  private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();

  private final User user = new User(UUID.randomUUID(), "a@b.com", "A", null, User.CUSTOMER);

  @Test
  void issueGivesSignedAccessTokenWithSubjectRoleAndExpiry() {
    var out = service.issue(user, "dev-1");
    var jwt = decoder.decode(out.accessToken());
    assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
    assertThat(jwt.<String>getClaim("role")).isEqualTo("CUSTOMER");
    assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(900));
    assertThat(out.expiresInSeconds()).isEqualTo(900);
    assertThat(out.user()).isEqualTo(user);
  }

  @Test
  void issueStoresOnlyTheHashOfTheRefreshToken() {
    var out = service.issue(user, "dev-1");
    assertThat(out.refreshToken()).hasSizeGreaterThanOrEqualTo(40);
    verify(refresh).insert(user.id(), OtpHasher.sha256Hex(out.refreshToken()), "dev-1",
        NOW.plus(Duration.ofDays(30)), NOW);
  }

  @Test
  void everyIssuedRefreshTokenIsDifferent() {
    assertThat(service.issue(user, "d").refreshToken()).isNotEqualTo(service.issue(user, "d").refreshToken());
  }

  private RefreshTokenRepository.Row row(boolean revoked, Instant expires) {
    return new RefreshTokenRepository.Row(UUID.randomUUID(), user.id(), "dev-1", expires, revoked);
  }

  @Test
  void refreshRotates() {
    var r = row(false, NOW.plusSeconds(100));
    when(refresh.findByHash(OtpHasher.sha256Hex("tok"))).thenReturn(Optional.of(r));
    when(users.findById(user.id())).thenReturn(Optional.of(user));
    var out = service.refresh("tok");
    verify(refresh).revoke(r.id(), NOW);
    verify(refresh).insert(eq(user.id()), anyString(), eq("dev-1"), any(), eq(NOW));
    assertThat(out.refreshToken()).isNotEqualTo("tok");
  }

  @Test
  void unknownTokenIsUnauthenticated() {
    when(refresh.findByHash(anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.refresh("nope")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("UNAUTHENTICATED"));
  }

  @Test
  void reusedTokenRevokesTheWholeDevice() {
    var r = row(true, NOW.plusSeconds(100));
    when(refresh.findByHash(OtpHasher.sha256Hex("tok"))).thenReturn(Optional.of(r));
    assertThatThrownBy(() -> service.refresh("tok")).isInstanceOf(ApiException.class);
    verify(refresh).revokeDevice(user.id(), "dev-1", NOW);
    verify(refresh, never()).insert(any(), any(), any(), any(), any());
  }

  @Test
  void expiredTokenIsRejected() {
    when(refresh.findByHash(anyString())).thenReturn(Optional.of(row(false, NOW.minusSeconds(1))));
    assertThatThrownBy(() -> service.refresh("tok")).isInstanceOf(ApiException.class);
    verify(refresh, never()).revoke(any(), any());
  }

  @Test
  void tokenOfDeletedUserIsRejected() {
    when(refresh.findByHash(anyString())).thenReturn(Optional.of(row(false, NOW.plusSeconds(5))));
    when(users.findById(user.id())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.refresh("tok")).isInstanceOf(ApiException.class);
  }

  @Test
  void blockedUserCannotRefresh() {
    when(refresh.findByHash(anyString())).thenReturn(Optional.of(row(false, NOW.plusSeconds(5))));
    when(users.findById(user.id())).thenReturn(Optional.of(user));
    when(users.isBlocked(user.id())).thenReturn(true);
    assertThatThrownBy(() -> service.refresh("tok")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void logoutRevokesKnownTokenAndIgnoresUnknown() {
    var r = row(false, NOW.plusSeconds(5));
    when(refresh.findByHash(OtpHasher.sha256Hex("known"))).thenReturn(Optional.of(r));
    when(refresh.findByHash(OtpHasher.sha256Hex("unknown"))).thenReturn(Optional.empty());
    assertThat(service.logout("known")).contains(r.userId());
    assertThat(service.logout("unknown")).isEmpty();
    verify(refresh).revoke(r.id(), NOW);
    verify(refresh, Mockito.times(1)).revoke(any(), any());
  }
}
