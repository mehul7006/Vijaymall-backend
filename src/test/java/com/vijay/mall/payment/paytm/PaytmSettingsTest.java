package com.vijay.mall.payment.paytm;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.payment.paytm.app.PaytmSettings;
import com.vijay.mall.payment.paytm.domain.PaytmResultCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Defaults of the Paytm configuration and the result-code table. */
class PaytmSettingsTest {
  @Test
  void defaultsPerEnvironmentAndOverrides() {
    Map<String, String> v = PaytmTestSettings.values("");
    PaytmSettings s = PaytmTestSettings.of(v);
    assertThat(s.baseUrl()).isEqualTo("https://securestage.paytmpayments.com");
    v.put("paytm.environment", "production");
    assertThat(s.environment()).isEqualTo("PRODUCTION");
    assertThat(s.baseUrl()).isEqualTo("https://secure.paytmpayments.com");
    v.put("paytm.baseUrl", "https://example.test/");
    assertThat(s.baseUrl()).isEqualTo("https://example.test");
    assertThat(s.settlementBaseUrl()).isEqualTo("https://secure.paytmpayments.com");
    v.put("paytm.settlement.baseUrl", "https://settle.test/");
    assertThat(s.settlementBaseUrl()).isEqualTo("https://settle.test");
    assertThat(s.pollSeconds()).isEqualTo(10);
    assertThat(s.waitSeconds()).isEqualTo(180);
    assertThat(s.paymentMode()).isEqualTo("ALL");
    assertThat(s.autoAccept()).isTrue();
    assertThat(s.settlementAuth()).isEqualTo("BEARER");
    assertThat(s.settlementEnabled()).isFalse();
    assertThat(s.syncDays()).isEqualTo(7);
    assertThat(s.settlementToken()).isEmpty();
    assertThat(s.ready()).isTrue();
    v.put("paytm.mid", "");
    v.put("paytm.merchantKey", "");
    v.put("paytm.channelId", "");
    assertThat(s.missing()).containsExactly("merchant id", "merchant key", "channel id");
    assertThat(s.ready()).isFalse();
  }

  @Test
  void resultCodeTable() {
    assertThat(PaytmResultCode.of("0009")).contains(PaytmResultCode.ACCEPTED);
    assertThat(PaytmResultCode.of("0030").orElseThrow().outcome).isEqualTo(PaytmResultCode.Outcome.PENDING);
    assertThat(PaytmResultCode.of("0012").orElseThrow().outcome).isEqualTo(PaytmResultCode.Outcome.RETRY);
    assertThat(PaytmResultCode.of("9999")).isEmpty();
  }
}
