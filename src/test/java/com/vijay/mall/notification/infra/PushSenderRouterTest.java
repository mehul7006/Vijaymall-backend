package com.vijay.mall.notification.infra;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vijay.mall.config.ConnectionTester;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PushSenderRouterTest {
  RuntimeSettings settings = mock(RuntimeSettings.class);
  FcmPushSender fcm = mock(FcmPushSender.class);
  LoggingPushSender logging = mock(LoggingPushSender.class);
  PushSenderRouter router = new PushSenderRouter(settings, fcm, logging);

  @Test
  void usesFirebaseWhenAServiceAccountIsSaved() {
    when(settings.isSet(Settings.FCM_SERVICE_ACCOUNT)).thenReturn(true);
    router.send(List.of("t"), "a", "b", Map.of());
    verify(fcm).send(List.of("t"), "a", "b", Map.of());
    verifyNoInteractions(logging);
  }

  @Test
  void fallsBackToTheLogWhenNothingIsSaved() {
    when(settings.isSet(Settings.FCM_SERVICE_ACCOUNT)).thenReturn(false);
    router.send(List.of("t"), "a", "b", Map.of());
    verify(logging).send(List.of("t"), "a", "b", Map.of());
    verifyNoInteractions(fcm);
  }

  @Test
  void switchesLiveWhenTheSettingChanges() {
    when(settings.isSet(Settings.FCM_SERVICE_ACCOUNT)).thenReturn(false, true);
    router.send(List.of("t"), "a", "b", Map.of());
    router.send(List.of("t"), "a", "b", Map.of());
    verify(logging).send(List.of("t"), "a", "b", Map.of());
    verify(fcm).send(List.of("t"), "a", "b", Map.of());
  }

  @Test
  void testerIsNamedFcmAndDelegates() {
    FcmConnectionTester tester = new FcmConnectionTester(fcm);
    ConnectionTester.Result r = new ConnectionTester.Result(true, "ok");
    when(fcm.validate()).thenReturn(r);
    assertThat(tester.target()).isEqualTo("fcm");
    assertThat(tester.test(Map.of())).isSameAs(r);
  }
}
