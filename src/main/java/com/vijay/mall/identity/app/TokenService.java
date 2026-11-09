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
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes the tokens that keep a customer signed in.
 * <p>
 * The access token is a short-lived HS256 JWT carrying the user id and role. The refresh token is a random opaque
 * string; only its SHA-256 hash is stored. Each refresh rotates the token, and presenting an already used one is treated
 * as theft and revokes every token of that device.
 */
@Service
public class TokenService {
  /**
   * What a successful sign-in or refresh returns to the app.
   * @param accessToken the JWT to send as a Bearer token
   * @param refreshToken the opaque token to get the next pair
   * @param expiresInSeconds lifetime of the access token in seconds
   * @param user the signed-in user
   */
  public record AuthResult(String accessToken, String refreshToken, long expiresInSeconds, User user) {}

  private final JwtEncoder encoder;
  private final AppProps props;
  private final Clock clock;
  private final RefreshTokenRepository refreshTokens;
  private final UserRepository users;
  private final SecureRandom random = new SecureRandom();

  /** Creates the service (wired by Spring). */
  public TokenService(JwtEncoder encoder, AppProps props, Clock clock, RefreshTokenRepository refreshTokens,
                      UserRepository users) {
    this.encoder = encoder;
    this.props = props;
    this.clock = clock;
    this.refreshTokens = refreshTokens;
    this.users = users;
  }

  /**
   * Creates a new access token and refresh token for the user and stores the refresh token's hash.
   * @param user the user to sign in
   * @param deviceId optional device id kept with the refresh token
   */
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

  /**
   * Revokes the given refresh token; an unknown token is ignored.
   * @return the user the token belongs to, or empty when the token is unknown
   */
  @Transactional
  public Optional<UUID> logout(String refreshToken) {
    return refreshTokens.findByHash(OtpHasher.sha256Hex(refreshToken)).map(r -> {
      refreshTokens.revoke(r.id(), clock.instant());
      return r.userId();
    });
  }
}
