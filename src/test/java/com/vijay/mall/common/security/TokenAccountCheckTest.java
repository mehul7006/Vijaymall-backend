package com.vijay.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.identity.infra.UserRepository;
import java.io.IOException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

/** The blocked-account check inside the token filter fails closed and answers a full pool with 503, never a container 500. */
class TokenAccountCheckTest {
  private final UserRepository users = mock(UserRepository.class);
  private final AppCaches caches = new AppCaches(Clock.systemUTC());
  private final UUID id = UUID.randomUUID();

  private Jwt jwt(String role) {
    return Jwt.withTokenValue("t").header("alg", "HS256").subject(id.toString()).claim("role", role).build();
  }

  @Test
  void anAccountThatIsNotBlockedPasses() {
    when(users.isBlocked(id)).thenReturn(false);
    SecurityConfig.requireNotBlocked(jwt("CUSTOMER"), users, caches);
    SecurityConfig.requireNotBlocked(jwt("ADMIN"), users, caches);
  }

  @Test
  void aBlockedAccountIsRefused() {
    when(users.isBlocked(id)).thenReturn(true);
    assertThatThrownBy(() -> SecurityConfig.requireNotBlocked(jwt("ADMIN"), users, caches)).isInstanceOf(DisabledException.class);
    assertThatThrownBy(() -> SecurityConfig.requireNotBlocked(jwt("CUSTOMER"), users, caches)).isInstanceOf(DisabledException.class);
  }

  @Test
  void aFullPoolIsABusyAnswerForStaffAndCustomersAndIsNeverTreatedAsNotBlocked() {
    var pool = new CannotGetJdbcConnectionException("pool", new SQLTransientConnectionException("timed out"));
    when(users.isBlocked(any())).thenThrow(pool);
    assertThatThrownBy(() -> SecurityConfig.requireNotBlocked(jwt("CASHIER"), users, caches))
        .isInstanceOf(DatabaseBusyAuthenticationException.class).hasCause(pool);
    assertThatThrownBy(() -> SecurityConfig.requireNotBlocked(jwt("CUSTOMER"), users, caches))
        .isInstanceOf(DatabaseBusyAuthenticationException.class);
  }

  @Test
  void anyOtherLookupFailureFailsClosedAsAnError() {
    when(users.isBlocked(any())).thenThrow(new IllegalStateException("db down"));
    assertThatThrownBy(() -> SecurityConfig.requireNotBlocked(jwt("CUSTOMER"), users, caches)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theBusyAnswerIs503WithRetryAfterAndTheStandardEnvelope() throws IOException {
    var res = new MockHttpServletResponse();
    SecurityConfig.writeBusy(res, JsonMapper.builder().build());
    assertThat(res.getStatus()).isEqualTo(503);
    assertThat(res.getHeader("Retry-After")).isEqualTo("2");
    assertThat(res.getContentType()).isEqualTo("application/json");
    assertThat(res.getContentAsString()).contains("\"SERVICE_BUSY\"").contains("\"retryAfterSeconds\":2");
  }
}
