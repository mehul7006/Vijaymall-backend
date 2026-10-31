package com.vijay.mall.identity.app;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.OtpHasher;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.RefreshTokenRepository;
import com.vijay.mall.identity.infra.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenService {
  public record AuthResult(String accessToken, String refreshToken, long expiresInSeconds, User user) {}

  private final JwtEncoder encoder;
  private final AppProps props;
  private final Clock clock;
  private final RefreshTokenRepository refreshTokens;
  private final UserRepository users;
  private final SecureRandom random = new SecureRandom();

  public TokenService(JwtEncoder encoder, AppProps props, Clock clock, RefreshTokenRepository refreshTokens,
                      UserRepository users) {
    this.encoder = encoder;
    this.props = props;
    this.clock = clock;
    this.refreshTokens = refreshTokens;
    this.users = users;
  }

  @Transactional
  public AuthResult issue(User user, String deviceId) {
    Instant now = clock.instant();
    long ttl = props.jwt().accessTtlSeconds();
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .subject(user.id().toString())
        .claim("role", user.role())
        .issuedAt(now)
        .expiresAt(now.plusSeconds(ttl))
        .build();
    String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();

    byte[] raw = new byte[32];
    random.nextBytes(raw);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    refreshTokens.insert(user.id(), OtpHasher.sha256Hex(refresh), deviceId,
        now.plus(Duration.ofDays(props.jwt().refreshTtlDays())), now);
    return new AuthResult(access, refresh, ttl, user);
  }

  /** Rotates a refresh token. A reused (already revoked) token revokes the whole device chain. */
  @Transactional(noRollbackFor = ApiException.class)
  public AuthResult refresh(String refreshToken) {
    Instant now = clock.instant();
    var row = refreshTokens.findByHash(OtpHasher.sha256Hex(refreshToken)).orElseThrow(ApiException::unauthenticated);
    if (row.revoked()) {
      refreshTokens.revokeDevice(row.userId(), row.deviceId(), now);
      throw ApiException.unauthenticated();
    }
    if (row.expiresAt().isBefore(now)) throw ApiException.unauthenticated();
    refreshTokens.revoke(row.id(), now);
    User user = users.findById(row.userId()).orElseThrow(ApiException::unauthenticated);
    if (users.isBlocked(user.id())) throw ApiException.forbidden();
    return issue(user, row.deviceId());
  }

  @Transactional
  public void logout(String refreshToken) {
    refreshTokens.findByHash(OtpHasher.sha256Hex(refreshToken))
        .ifPresent(r -> refreshTokens.revoke(r.id(), clock.instant()));
  }
}
