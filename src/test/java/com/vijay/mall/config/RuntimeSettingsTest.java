package com.vijay.mall.config;

import static com.vijay.mall.config.Settings.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings.Change;
import com.vijay.mall.config.RuntimeSettings.Source;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RuntimeSettingsTest {
  private final UUID admin = UUID.randomUUID();
  private final SettingsFixture f = new SettingsFixture();
  private final RuntimeSettings s = f.settings;

  private static Change c(Def d, String v) { return new Change(d.key(), v); }

  // ---------- precedence ----------
  @Test
  void theAdminsValueBeatsTheServersOwnWhichBeatsTheBuiltInDefault() {
    assertThat(s.get(DELIVERY_FEE)).isEqualTo("0");
    assertThat(s.sourceOf(DELIVERY_FEE)).isEqualTo(Source.DEFAULT);
    f.env.setProperty("app.delivery.fee-paise", "4000");
    assertThat(s.get(DELIVERY_FEE)).isEqualTo("4000");
    assertThat(s.sourceOf(DELIVERY_FEE)).isEqualTo(Source.ENVIRONMENT);
    f.rows.add(SettingsFixture.entity("delivery.feePaise", "5500", false));
    s.refresh();
    assertThat(s.getInt(DELIVERY_FEE)).isEqualTo(5500);
    assertThat(s.getLong(DELIVERY_FEE)).isEqualTo(5500L);
    assertThat(s.sourceOf(DELIVERY_FEE)).isEqualTo(Source.DATABASE);
  }

  @Test
  void typedReadersFallBackToTheDefaultOnGarbage() {
    f.rows.add(SettingsFixture.entity("delivery.feePaise", "abc", false));
    f.rows.add(SettingsFixture.entity("pos.requireOpenShift", "TRUE", false));
    s.refresh();
    assertThat(s.getInt(DELIVERY_FEE)).isZero();
    assertThat(s.getLong(DELIVERY_FEE)).isZero();
    assertThat(s.getBool(POS_REQUIRE_OPEN_SHIFT)).isTrue();
    assertThat(s.getBool(CACHE_ENABLED)).isTrue();
    assertThat(s.isSet(RAZORPAY_KEY_ID)).isFalse();
  }

  @Test
  void rowsOfASettingThisVersionDoesNotKnowAreIgnored() {
    f.rows.add(SettingsFixture.entity("old.removed.setting", "x", false));
    s.refresh();
    assertThat(s.view()).extracting(RuntimeSettings.Row::key).doesNotContain("old.removed.setting");
  }

  @Test
  void aDatabaseFailureKeepsWhatWasLoaded() {
    f.rows.add(SettingsFixture.entity("delivery.feePaise", "700", false));
    s.refresh();
    when(f.jpa.findAll()).thenThrow(new IllegalStateException("db down"));
    s.refresh();
    assertThat(s.getInt(DELIVERY_FEE)).isEqualTo(700);
  }

  @Test
  void aFirstReadLoadsTheTableOnItsOwn() {
    f.rows.add(SettingsFixture.entity("delivery.feePaise", "900", false));
    assertThat(s.getInt(DELIVERY_FEE)).isEqualTo(900);
  }

  // ---------- the admin screen ----------
  @Test
  void theScreenListsEverySettingAndNeverTheValueOfASecret() {
    s.save(List.of(c(RAZORPAY_KEY_ID, "rzp_test_abc"), c(RAZORPAY_KEY_SECRET, "topsecret")), admin);
    var rows = s.view();
    assertThat(rows).hasSameSizeAs(Settings.ALL);
    var secret = rows.stream().filter(r -> r.key().equals("razorpay.keySecret")).findFirst().orElseThrow();
    assertThat(secret.secret()).isTrue();
    assertThat(secret.set()).isTrue();
    assertThat(secret.value()).isNull();
    assertThat(secret.source()).isEqualTo("DATABASE");
    var id = rows.stream().filter(r -> r.key().equals("razorpay.keyId")).findFirst().orElseThrow();
    assertThat(id.value()).isEqualTo("rzp_test_abc");
    var fee = rows.stream().filter(r -> r.key().equals("delivery.feePaise")).findFirst().orElseThrow();
    assertThat(fee.min()).isZero();
    assertThat(fee.max()).isEqualTo(1_000_000L);
    var level = rows.stream().filter(r -> r.key().equals("log.level.app")).findFirst().orElseThrow();
    assertThat(level.allowed()).contains("DEBUG", "INFO");
    assertThat(level.min()).isNull();
  }

  // ---------- saving ----------
  @Test
  void aSecretIsStoredEncryptedAndReadBackInPlainOnlyInsideTheServer() {
    var changed = s.save(List.of(c(RAZORPAY_KEY_SECRET, "  topsecret  ")), admin);
    assertThat(changed).containsExactly("razorpay.keySecret");
    var row = f.row("razorpay.keySecret").orElseThrow();
    assertThat(row.secret).isTrue();
    assertThat(row.value).doesNotContain("topsecret");
    assertThat(s.get(RAZORPAY_KEY_SECRET)).isEqualTo("topsecret");
    assertThat(row.updatedBy).isEqualTo(admin);
    verify(f.events).publishEvent(new SettingsChanged(Set.of("razorpay.keySecret")));
    ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
    verify(f.audit).log(eq(admin), eq("CONFIG_CHANGE"), eq("app_setting"), eq("razorpay.keySecret"), detail.capture());
    assertThat(detail.getValue()).isEqualTo("changed").doesNotContain("topsecret");
  }

  @Test
  void savingTheSameValueAgainChangesNothingAndSendsNoEvent() {
    s.save(List.of(c(RAZORPAY_KEY_SECRET, "topsecret"), c(DELIVERY_FEE, "4000")), admin);
    org.mockito.Mockito.clearInvocations(f.events, f.audit);
    assertThat(s.save(List.of(c(RAZORPAY_KEY_SECRET, "topsecret"), c(DELIVERY_FEE, "4000")), admin)).isEmpty();
    verify(f.events, never()).publishEvent(any(Object.class));
    verify(f.audit, never()).log(any(), any(), any(), any(), any());
  }

  @Test
  void aChangedValueIsAuditedWithBeforeAndAfterForPlainSettings() {
    s.save(List.of(c(DELIVERY_FEE, "4000")), admin);
    s.save(List.of(c(DELIVERY_FEE, "5000")), admin);
    verify(f.audit).log(admin, "CONFIG_CHANGE", "app_setting", "delivery.feePaise", "4000 -> 5000");
    verify(f.audit).log(admin, "CONFIG_CHANGE", "app_setting", "delivery.feePaise", "4000");
  }

  @Test
  void aBlankValueClearsTheSettingBackToTheServersOwn() {
    s.save(List.of(c(DELIVERY_FEE, "4000"), c(RAZORPAY_KEY_SECRET, "topsecret")), admin);
    assertThat(s.save(List.of(c(DELIVERY_FEE, "  "), c(RAZORPAY_KEY_SECRET, "")), admin)).containsExactlyInAnyOrder("delivery.feePaise", "razorpay.keySecret");
    assertThat(f.rows).isEmpty();
    assertThat(s.get(DELIVERY_FEE)).isEqualTo("0");
    verify(f.audit).log(admin, "CONFIG_CLEAR", "app_setting", "razorpay.keySecret", "cleared");
    verify(f.audit).log(admin, "CONFIG_CLEAR", "app_setting", "delivery.feePaise", "was 4000");
    assertThat(s.save(List.of(c(DELIVERY_FEE, "")), admin)).as("nothing to clear").isEmpty();
  }

  @Test
  void anythingWrongRefusesTheWholeSaveAndStoresNothing() {
    assertThatThrownBy(() -> s.save(List.of(c(DELIVERY_FEE, "4000"), c(POS_CASHIER_RETURN_DAYS, "9999")), admin)).isInstanceOf(ApiException.class);
    assertThat(f.rows).isEmpty();
    assertThatThrownBy(() -> s.save(List.of(), admin)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> s.save(null, admin)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> s.save(List.of(new Change("no.such", "1")), admin)).hasMessageContaining("unknown setting");
    assertThatThrownBy(() -> s.save(List.of(new Change(null, "1")), admin)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> s.save(List.of(c(DELIVERY_FEE, "1"), c(DELIVERY_FEE, "2")), admin)).hasMessageContaining("listed twice");
  }

  @Test
  void aSecretCannotBeSavedWhenTheServerHasNoEncryptionKey() {
    var none = new SettingsFixture("");
    assertThatThrownBy(() -> none.settings.save(List.of(c(RAZORPAY_KEY_SECRET, "x")), admin)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("CONFIG_KEY_MISSING"));
    assertThat(none.settings.save(List.of(c(DELIVERY_FEE, "10")), admin)).containsExactly("delivery.feePaise");
    assertThat(none.settings.save(List.of(c(RAZORPAY_KEY_SECRET, "")), admin)).isEmpty();
    assertThat(none.settings.issues()).anyMatch(i -> i.contains("CONFIG_ENCRYPTION_KEY"));
  }

  // ---------- validation ----------
  @Test
  void numbersMustBeWholeAndInRange() {
    assertThat(s.validate(DELIVERY_FEE, " 0040 ")).isEqualTo("40");
    assertThatThrownBy(() -> s.validate(DELIVERY_FEE, "4.5")).hasMessageContaining("whole number");
    assertThatThrownBy(() -> s.validate(DELIVERY_FEE, "-1")).hasMessageContaining("between");
    assertThatThrownBy(() -> s.validate(ONLINE_TIMEOUT_MINUTES, "4")).hasMessageContaining("between 5 and 120");
    assertThat(s.validate(ONLINE_TIMEOUT_MINUTES, "120")).isEqualTo("120");
  }

  @Test
  void choicesAreMatchedIgnoringCaseAndStoredInTheirOwnSpelling() {
    assertThat(s.validate(LOG_LEVEL_APP, "debug")).isEqualTo("DEBUG");
    assertThat(s.validate(SMS_CHANNEL, "HTTP")).isEqualTo("http");
    assertThat(s.validate(POS_REQUIRE_OPEN_SHIFT, "True")).isEqualTo("true");
    assertThat(s.validate(LOG_RETENTION_DAYS, "15")).isEqualTo("15");
    assertThatThrownBy(() -> s.validate(LOG_RETENTION_DAYS, "10")).hasMessageContaining("7, 15, 30");
    assertThatThrownBy(() -> s.validate(GST_ROUND_OFF, "ROUND_UP")).isInstanceOf(ApiException.class);
  }

  @Test
  void textIsLimitedInLengthAndFreeOfControlCharacters() {
    assertThatThrownBy(() -> s.validate(SMS_SENDER_ID, "x".repeat(21))).hasMessageContaining("too long");
    assertThatThrownBy(() -> s.validate(SMS_SENDER_ID, "ab\u0007cd")).hasMessageContaining("not allowed");
    assertThat(s.validate(SMS_HTTP_BODY, "{\n  \"a\": 1\n}")).contains("\n");
  }

  @Test
  void specialSettingsHaveTheirOwnChecks() {
    assertThatThrownBy(() -> s.validate(RAZORPAY_KEY_ID, "abc123")).hasMessageContaining("rzp_");
    assertThat(s.validate(RAZORPAY_KEY_ID, "rzp_test_1")).isEqualTo("rzp_test_1");
    assertThatThrownBy(() -> s.validate(SMS_HTTP_URL, "http://sms.example.com/send")).hasMessageContaining("https://");
    assertThat(s.validate(SMS_HTTP_URL, "https://sms.example.com/send")).startsWith("https://");
    assertThat(s.validate(SMS_HTTP_URL, "http://localhost:9999/x")).startsWith("http://localhost");
    assertThat(s.validate(SMS_HTTP_URL, "http://127.0.0.1:9/x")).startsWith("http://127");
    assertThatThrownBy(() -> s.validate(SMS_HTTP_HEADERS, "authkey=1")).hasMessageContaining("not valid JSON");
    assertThatThrownBy(() -> s.validate(SMS_HTTP_HEADERS, "[1,2]")).hasMessageContaining("JSON object");
    assertThat(s.validate(SMS_HTTP_HEADERS, "{\"authkey\":\"k\"}")).contains("authkey");
    assertThatThrownBy(() -> s.validate(FCM_SERVICE_ACCOUNT, "{\"project_id\":\"p\"}")).hasMessageContaining("client_email");
    assertThatThrownBy(() -> s.validate(FCM_SERVICE_ACCOUNT, "{\"project_id\":\"p\",\"client_email\":\"e\",\"private_key\":\" \"}")).hasMessageContaining("private_key");
    assertThat(s.validate(FCM_SERVICE_ACCOUNT, "{\"project_id\":\"p\",\"client_email\":\"e@x\",\"private_key\":\"k\"}")).contains("project_id");
    assertThatThrownBy(() -> s.validate(CLIENT_IP_HEADER, "bad header")).hasMessageContaining("header name");
    assertThat(s.validate(CLIENT_IP_HEADER, "CF-Connecting-IP")).isEqualTo("CF-Connecting-IP");
  }

  // ---------- half finished set-ups ----------
  @Test
  void issuesNamePlainlyWhatWillNotWork() {
    assertThat(s.issues()).isEmpty();
    s.save(List.of(c(RAZORPAY_KEY_ID, "rzp_test_1")), admin);
    assertThat(s.issues()).anyMatch(i -> i.contains("both the key id and the key secret"));
    s.save(List.of(c(RAZORPAY_KEY_SECRET, "sec")), admin);
    assertThat(s.issues()).anyMatch(i -> i.contains("webhook secret is missing"));
    s.save(List.of(c(RAZORPAY_WEBHOOK_SECRET, "whsec")), admin);
    assertThat(s.issues()).isEmpty();

    s.save(List.of(c(SMS_CHANNEL, "http")), admin);
    assertThat(s.issues()).anyMatch(i -> i.contains("provider address is empty")).anyMatch(i -> i.contains("DLT template id"));
    s.save(List.of(c(SMS_HTTP_URL, "https://sms.example.com/x"), c(SMS_SENDER_ID, "VIJAYM"), c(SMS_DLT_TEMPLATE_ID, "1007")), admin);
    assertThat(s.issues()).isEmpty();

    s.save(List.of(c(OTP_CHANNEL, "email")), admin);
    assertThat(s.issues()).anyMatch(i -> i.contains("SMTP server is still localhost"));
    s.save(List.of(c(SMTP_HOST, "smtp.example.com")), admin);
    assertThat(s.issues()).isEmpty();
  }

  @Test
  void secretsThatCannotBeReadAreReportedAndTreatedAsNotSet() {
    f.rows.add(SettingsFixture.entity("razorpay.keySecret", "garbage-not-encrypted", true));
    s.refresh();
    assertThat(s.isSet(RAZORPAY_KEY_SECRET)).isFalse();
    assertThat(s.issues()).anyMatch(i -> i.contains("could not be read") && i.contains("razorpay.keySecret"));
  }

  @Test
  void theIssueCountIsAMetricForTheDashboard() {
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    s.bindTo(registry);
    assertThat(registry.get("mall.config.issues").gauge().value()).isZero();
    s.save(List.of(c(RAZORPAY_KEY_ID, "rzp_test_1")), admin);
    assertThat(registry.get("mall.config.issues").gauge().value()).isEqualTo(1);
    assertThat(registry.get("mall.config.changes").tag("group", "PAYMENT").counter().count()).isEqualTo(1);
  }

  // ---------- remaining corners ----------
  @Test
  void aDefinitionWithoutAnyFallbackReadsAsEmpty() {
    Def bare = new Def("x.bare", Group.SECURITY, Type.STRING, "Bare", "", "no.such.property", null, List.of(), 0, 0, 10, false);
    assertThat(s.get(bare)).isEmpty();
    assertThat(s.isSet(bare)).isFalse();
  }

  @Test
  void smsIssuesNameTheMissingSenderOrTemplateOnEitherSide() {
    s.save(List.of(c(SMS_CHANNEL, "http"), c(SMS_HTTP_URL, "https://sms.example.com/x"), c(SMS_SENDER_ID, "VIJAYM")), admin);
    assertThat(s.issues()).anyMatch(i -> i.contains("DLT template id"));
    s.save(List.of(c(SMS_SENDER_ID, ""), c(SMS_DLT_TEMPLATE_ID, "1007")), admin);
    assertThat(s.issues()).anyMatch(i -> i.contains("DLT template id"));
    s.save(List.of(c(SMS_SENDER_ID, "VIJAYM")), admin);
    assertThat(s.issues()).isEmpty();
  }

  @Test
  void aNullValueMeansClear() {
    s.save(List.of(c(DELIVERY_FEE, "4000")), admin);
    assertThat(s.save(List.of(new Change(DELIVERY_FEE.key(), null)), admin)).containsExactly("delivery.feePaise");
    assertThat(f.rows).isEmpty();
  }

  @Test
  void aChangedSecretIsStoredAgainAndASecretThatCannotBeReadIsReplaced() {
    s.save(List.of(c(RAZORPAY_KEY_SECRET, "first")), admin);
    assertThat(s.save(List.of(c(RAZORPAY_KEY_SECRET, "second")), admin)).containsExactly("razorpay.keySecret");
    assertThat(s.get(RAZORPAY_KEY_SECRET)).isEqualTo("second");
    f.rows.clear();
    f.rows.add(SettingsFixture.entity("razorpay.keySecret", "garbage-not-encrypted", true));
    assertThat(s.save(List.of(c(RAZORPAY_KEY_SECRET, "third")), admin)).containsExactly("razorpay.keySecret");
    assertThat(s.get(RAZORPAY_KEY_SECRET)).isEqualTo("third");
  }

  @Test
  void longPlainValuesAreShortenedInTheAuditTrail() {
    String longBody = "a".repeat(130);
    s.save(List.of(c(SMS_HTTP_BODY, longBody)), admin);
    s.save(List.of(c(SMS_HTTP_BODY, "b".repeat(130))), admin);
    verify(f.audit).log(admin, "CONFIG_CHANGE", "app_setting", "sms.http.body", "a".repeat(120) + "... -> " + "b".repeat(120) + "...");
    s.save(List.of(c(SMS_HTTP_BODY, "")), admin);
    verify(f.audit).log(admin, "CONFIG_CLEAR", "app_setting", "sms.http.body", "was " + "b".repeat(120) + "...");
  }

  @Test
  void tabsAndCarriageReturnsAreAllowedInText() {
    assertThat(s.validate(SMS_HTTP_BODY, "a\tb\r\nc")).contains("\t").contains("\r");
    assertThatThrownBy(() -> s.validate(SMS_HTTP_BODY, "a\u0000b")).hasMessageContaining("not allowed");
  }

  @Test
  void aBlankEnvironmentValueCountsAsNotSet() {
    f.env.setProperty(DELIVERY_FEE.envProperty(), "  ");
    assertThat(s.get(DELIVERY_FEE)).isEqualTo(DELIVERY_FEE.fallback());
    assertThat(s.sourceOf(DELIVERY_FEE)).isEqualTo(Source.DEFAULT);
  }
}
