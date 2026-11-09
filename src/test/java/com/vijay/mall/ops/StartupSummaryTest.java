package com.vijay.mall.ops;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vijay.mall.common.AppProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

class StartupSummaryTest {
  private final Logger logger = (Logger) LoggerFactory.getLogger(StartupSummary.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void attach() { logs.start(); logger.addAppender(logs); }

  @AfterEach
  void detach() { logger.detachAppender(logs); }

  private static AppProps props(AppProps.Payment pay) {
    return new AppProps(new AppProps.Jwt("s", 900, 30), new AppProps.Otp("email", "p", "", "x@y.z"), "a@b.c", "./img", pay,
        new AppProps.Delivery(0, 49900));
  }

  @Test
  void itSaysHowTheServerIsSetUpWithoutRevealingASecret() {
    var env = new MockEnvironment().withProperty("app.cors.allowed-origin-patterns", "https://a.in,https://b.in")
        .withProperty("app.otp.sms-channel", "console");
    env.setActiveProfiles("prod");
    new StartupSummary(env, props(new AppProps.Payment("razorpay", 15, "rzp_live_SECRETKEY", "topsecret", "whsec", "https://api.razorpay.com")))
        .run(null);
    String line = logs.list.get(0).getFormattedMessage();
    assertThat(line).contains("profiles=[prod]").contains("otpChannel=email").contains("smsChannel=console")
        .contains("razorpayKeys=set").contains("razorpayWebhookSecret=set").contains("corsOrigins=2")
        .doesNotContain("SECRETKEY").doesNotContain("topsecret").doesNotContain("whsec");
    assertThat(logs.list.get(0).getKeyValuePairs()).anySatisfy(kv -> assertThat(kv.value).isEqualTo("startup-summary"));
  }

  @Test
  void missingKeysAndNoPaymentConfigAreReportedPlainly() {
    new StartupSummary(new MockEnvironment(), props(new AppProps.Payment("razorpay", 15, "", " ", null, "x"))).run(null);
    assertThat(logs.list.get(0).getFormattedMessage()).contains("razorpayKeys=missing").contains("razorpayWebhookSecret=missing")
        .contains("corsOrigins=0").contains("smsChannel=none");
    new StartupSummary(new MockEnvironment(), props(null)).run(null);
    assertThat(logs.list.get(1).getFormattedMessage()).contains("onlinePayment=?").contains("razorpayKeys=missing");
  }

  @Test
  void aKeyIdWithoutASecretCountsAsMissingAndAMissingOtpSectionShowsAQuestionMark() {
    new StartupSummary(new MockEnvironment(), props(new AppProps.Payment("razorpay", 15, "rzp_test_1", "", null, "x"))).run(null);
    assertThat(logs.list.get(0).getFormattedMessage()).contains("razorpayKeys=missing");
    var noOtp = new AppProps(new AppProps.Jwt("s", 900, 30), null, "a@b.c", "./img",
        new AppProps.Payment("razorpay", 15, "id", "sec", "wh", "x"), new AppProps.Delivery(0, 49900));
    new StartupSummary(new MockEnvironment(), noOtp).run(null);
    assertThat(logs.list.get(1).getFormattedMessage()).contains("otpChannel=?").contains("razorpayKeys=set");
  }
}
