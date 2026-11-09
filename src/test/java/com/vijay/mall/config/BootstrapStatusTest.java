package com.vijay.mall.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.config.BootstrapStatus.Item;
import com.vijay.mall.config.BootstrapStatus.State;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BootstrapStatusTest {
  private static Item find(List<Item> items, String setBy) { return items.stream().filter(i -> i.setBy().equals(setBy)).findFirst().orElseThrow(); }

  private static MockEnvironment good() {
    return new MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://db.internal:5432/mall?ssl=true")
        .withProperty("spring.datasource.password", "a-real-password").withProperty("app.jwt.secret", "k8s9d7f6g5h4j3k2l1m0n9b8v7c6x5z4q3w2e1r0")
        .withProperty("app.otp.pepper", "p3pp3r-p3pp3r-p3pp3r").withProperty("management.server.port", "9090").withProperty("server.port", "8080")
        .withProperty("app.bootstrap-admin-email", "owner@shop.in");
  }

  private static MockEnvironment goodInProd() {
    var env = good();
    env.setActiveProfiles("prod");
    return env;
  }

  @Test
  void aProperServerShowsEverythingOkAndNeverAValue() {
    var items = new BootstrapStatus(goodInProd(), new ConfigCrypto(SettingsFixture.KEY), "/data/images").items();
    assertThat(items).extracting(Item::state).containsOnly(State.OK);
    assertThat(find(items, "DB_URL").detail()).isEqualTo("server db.internal:5432");
    assertThat(items.toString()).doesNotContain("a-real-password").doesNotContain("p3pp3r").doesNotContain("k8s9d7");
  }

  @Test
  void defaultsAndMissingPiecesAreFlaggedPlainly() {
    var env = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/mall")
        .withProperty("spring.datasource.password", "mall").withProperty("app.jwt.secret", "change-me-change-me-change-me-change-me-0123456789")
        .withProperty("app.otp.pepper", "dev-pepper").withProperty("app.otp.fixed-code", "12345").withProperty("server.port", "8080");
    var items = new BootstrapStatus(env, new ConfigCrypto(""), "./data/images").items();
    assertThat(find(items, "DB_URL").state()).isEqualTo(State.DEFAULT);
    assertThat(find(items, "DB_USER, DB_PASSWORD").state()).isEqualTo(State.UNSAFE);
    assertThat(find(items, "JWT_SECRET").state()).isEqualTo(State.UNSAFE);
    assertThat(find(items, "OTP_PEPPER").state()).isEqualTo(State.UNSAFE);
    assertThat(find(items, "OTP_FIXED_CODE").state()).isEqualTo(State.UNSAFE);
    assertThat(find(items, "CONFIG_ENCRYPTION_KEY").state()).isEqualTo(State.MISSING);
    assertThat(find(items, "MANAGEMENT_PORT").state()).isEqualTo(State.UNSAFE);
    assertThat(find(items, "ADMIN_EMAIL").state()).isEqualTo(State.MISSING);
    assertThat(find(items, "SPRING_PROFILES_ACTIVE").state()).isEqualTo(State.DEFAULT);
  }

  @Test
  void anEmptyEnvironmentIsAllMissingAndASharedPortIsUnsafe() {
    var items = new BootstrapStatus(new MockEnvironment(), new ConfigCrypto(SettingsFixture.KEY), "x").items();
    assertThat(find(items, "DB_URL").state()).isEqualTo(State.MISSING);
    assertThat(find(items, "JWT_SECRET").state()).isEqualTo(State.MISSING);
    assertThat(find(items, "OTP_PEPPER").state()).isEqualTo(State.MISSING);
    var shared = new BootstrapStatus(new MockEnvironment().withProperty("management.server.port", "8080").withProperty("server.port", "8080"),
        new ConfigCrypto(SettingsFixture.KEY), "x").items();
    assertThat(find(shared, "MANAGEMENT_PORT").state()).isEqualTo(State.UNSAFE);
    var env = good();
    env.setActiveProfiles("prod");
    assertThat(find(new BootstrapStatus(env, new ConfigCrypto(SettingsFixture.KEY), "x").items(), "SPRING_PROFILES_ACTIVE").detail()).isEqualTo("prod");
  }

  @Test
  void loginCodesInTheLogAreFlaggedOutsideDevelopment() {
    var prod = goodInProd().withProperty("app.otp.channel", "console");
    assertThat(find(new BootstrapStatus(prod, new ConfigCrypto(SettingsFixture.KEY), "/data/images").items(), "OTP_CHANNEL, SMS_CHANNEL").state())
        .isEqualTo(State.UNSAFE);
    var sms = goodInProd().withProperty("app.otp.sms-channel", "console");
    assertThat(find(new BootstrapStatus(sms, new ConfigCrypto(SettingsFixture.KEY), "/data/images").items(), "OTP_CHANNEL, SMS_CHANNEL").state())
        .isEqualTo(State.UNSAFE);
    var dev = good().withProperty("app.otp.channel", "console");
    dev.setActiveProfiles("dev");
    var item = find(new BootstrapStatus(dev, new ConfigCrypto(SettingsFixture.KEY), "/data/images").items(), "OTP_CHANNEL, SMS_CHANNEL");
    assertThat(item.state()).isEqualTo(State.OK);
    assertThat(item.detail()).contains("dev server");
    var email = goodInProd().withProperty("app.otp.channel", "email");
    assertThat(find(new BootstrapStatus(email, new ConfigCrypto(SettingsFixture.KEY), "/data/images").items(), "OTP_CHANNEL, SMS_CHANNEL").detail())
        .startsWith("off");
  }
}
