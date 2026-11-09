package com.vijay.mall.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.ShopIdentity;
import com.vijay.mall.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SenderCoverageTest {
  private final JavaMailSender mail = mock(JavaMailSender.class);
  private final ShopIdentity shop = mock(ShopIdentity.class);
  private final FakeSettings fs = new FakeSettings().with(com.vijay.mall.config.Settings.OTP_FROM, "no-reply@shop.test");

  private SmtpEmailOtpSender sender(ShopIdentity s) {
    return new SmtpEmailOtpSender(fs.settings, s) {
      @Override protected JavaMailSender createMailSender(String h, int p, String u, String pw) { return mail; }
    };
  }

  private String subjectWithShopName(String name) {
    when(shop.identity()).thenReturn(new ShopIdentity.Info(name, null, null, null, null, null));
    sender(shop).send("a@b.com", "11111");
    var cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(cap.capture());
    return cap.getValue().getSubject();
  }

  @Test
  void subjectUsesTheShopNameFromSettings() {
    assertThat(subjectWithShopName("Sharma Store")).isEqualTo("Your Sharma Store code: 11111");
  }

  @Test
  void blankShopNameFallsBackToTheDefault() {
    assertThat(subjectWithShopName("  ")).isEqualTo("Your Vijay Super Mall code: 11111");
  }

  @Test
  void nullShopNameFallsBackToTheDefault() {
    assertThat(subjectWithShopName(null)).isEqualTo("Your Vijay Super Mall code: 11111");
  }

  @Test
  void withoutShopIdentityTheDefaultNameIsUsed() {
    sender(null).send("a@b.com", "22222");
    var cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(cap.capture());
    assertThat(cap.getValue().getSubject()).isEqualTo("Your Vijay Super Mall code: 22222");
  }

  @Test
  void unconfiguredSmsSenderRefusesWithServiceUnavailable() {
    assertThatThrownBy(() -> new UnconfiguredSmsOtpSender().send("9876543210", "12345"))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(e.code()).isEqualTo("SMS_NOT_CONFIGURED");
        });
  }
}
