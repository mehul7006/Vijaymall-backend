package com.vijay.mall.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.identity.app.AccountDeletionService;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.identity.app.TokenService;
import com.vijay.mall.support.TestAuth;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MeAuthControllerTest {
  private final ProfileService profile = mock(ProfileService.class);
  private final TokenService tokens = mock(TokenService.class);
  private final UUID user = UUID.randomUUID();
  private final AuthController auth = new AuthController(null, null, tokens, null, null, profile);

  @AfterEach
  void clear() { TestAuth.clear(); }

  @Test
  void deviceDeleteUnregistersTheCallersOwnToken() {
    TestAuth.login(user, "CUSTOMER");
    new MeController(profile, mock(AccountDeletionService.class)).unregisterDevice("tok:en");
    verify(profile).unregisterDevice(user, "tok:en");
  }

  @Test
  void logoutWithAPushTokenUnregistersItForTheTokensOwner() {
    when(tokens.logout("r1")).thenReturn(Optional.of(user));
    auth.logout(new AuthController.LogoutReq("r1", "fcm-1"));
    verify(profile).unregisterDevice(user, "fcm-1");
  }

  @Test
  void logoutWithoutAPushTokenOrWithAnUnknownRefreshTokenTouchesNoDevice() {
    when(tokens.logout("r1")).thenReturn(Optional.of(user));
    auth.logout(new AuthController.LogoutReq("r1", null));
    auth.logout(new AuthController.LogoutReq("r1", "  "));
    when(tokens.logout("r2")).thenReturn(Optional.empty());
    auth.logout(new AuthController.LogoutReq("r2", "fcm-1"));
    verify(profile, never()).unregisterDevice(any(), any());
  }

  @Test
  void anOldLogoutBodyWithOnlyTheRefreshTokenStillParses() {
    var body = JsonMapper.builder().build().readValue("{\"refreshToken\":\"abc\"}", AuthController.LogoutReq.class);
    assertThat(body.refreshToken()).isEqualTo("abc");
    assertThat(body.fcmToken()).isNull();
  }

  @Test
  void addressInputKeepsAPhoneAndTurnsBlankIntoNone() {
    assertThat(new MeController.AddressInput("H", "l", "a", "390001", true, "9876543210").toAddress().phone()).isEqualTo("9876543210");
    assertThat(new MeController.AddressInput("H", "l", "a", "390001", true, "").toAddress().phone()).isNull();
    var old = new MeController.AddressInput("H", "l", "a", "390001", null).toAddress();
    assertThat(old.phone()).isNull();
    assertThat(old.isDefault()).isFalse();
  }
}
