package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.ratelimit.RateLimiter;
import com.vijay.mall.identity.infra.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class ApprovalServiceTest {
  private final UUID admin = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();
  private final UUID cashier = UUID.randomUUID();
  private UserRepository users;
  private RateLimiter limiter;
  private ApprovalService service;

  @BeforeEach
  void setUp() {
    users = mock(UserRepository.class);
    limiter = mock(RateLimiter.class);
    service = new ApprovalService(users, limiter, mock(AuditService.class));
  }

  @Test
  void theStoredPinIsHashedNeverPlain() {
    service.setPin(admin, "4821");
    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(users).setApprovalPin(eq(admin), hash.capture());
    assertThat(hash.getValue()).isNotEqualTo("4821").startsWith("$2");
    assertThat(new BCryptPasswordEncoder().matches("4821", hash.getValue())).isTrue();
  }

  @Test
  void onlyFourToEightDigitsAreAccepted() {
    for (String bad : new String[] {null, "", "123", "123456789", "12a4", "12 34"}) {
      assertThatThrownBy(() -> service.setPin(admin, bad)).isInstanceOf(ApiException.class);
    }
    verify(users, never()).setApprovalPin(any(), anyString());
  }

  @Test
  void aPinIdentifiesTheAdminWhoOwnsIt() {
    var enc = new BCryptPasswordEncoder();
    when(users.adminPins()).thenReturn(List.of(new UserRepository.AdminPin(other, enc.encode("1111")),
        new UserRepository.AdminPin(admin, enc.encode("4821"))));
    assertThat(service.approverFor(cashier, "4821")).contains(admin);
    assertThat(service.approverFor(cashier, " 1111 ")).contains(other);
    assertThat(service.approverFor(cashier, "9999")).isEmpty();
    assertThat(service.approverFor(cashier, null)).isEmpty();
    assertThat(service.approverFor(cashier, "  ")).isEmpty();
  }

  @Test
  void everyAttemptCountsTowardsTheCashiersTenMinuteLimitAndTooManyIsRefused() {
    service.approverFor(cashier, "1234");
    verify(limiter).check("approval-pin:" + cashier, 5, 600);
    doThrow(ApiException.validation("x", "slow down")).when(limiter).check(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
    assertThatThrownBy(() -> service.approverFor(cashier, "1234")).isInstanceOf(ApiException.class);
    verify(users, org.mockito.Mockito.times(1)).adminPins(); // the refused attempt never even looked at the PINs
  }
}
