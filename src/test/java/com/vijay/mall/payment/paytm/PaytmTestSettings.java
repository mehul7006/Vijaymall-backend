package com.vijay.mall.payment.paytm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings.Def;
import com.vijay.mall.payment.paytm.app.PaytmSettings;
import java.util.HashMap;
import java.util.Map;

/** A {@link PaytmSettings} over a fake settings store, for plain unit tests: values default to the built-in defaults. */
public final class PaytmTestSettings {
  private PaytmTestSettings() {}

  /** The values by setting key; change them in the test, they are read on every call. */
  public static Map<String, String> values(String baseUrl) {
    Map<String, String> m = new HashMap<>();
    m.put("paytm.enabled", "true");
    m.put("paytm.baseUrl", baseUrl);
    m.put("paytm.mid", "TESTMID123");
    m.put("paytm.merchantKey", PaytmFakeServer.KEY);
    m.put("paytm.channelId", "EDC");
    return m;
  }

  /** Settings that read from {@code values}. */
  public static PaytmSettings of(Map<String, String> values) {
    RuntimeSettings rs = mock(RuntimeSettings.class);
    when(rs.get(any(Def.class))).thenAnswer(inv -> {
      Def d = inv.getArgument(0);
      return values.getOrDefault(d.key(), d.fallback() == null ? "" : d.fallback());
    });
    when(rs.getInt(any(Def.class))).thenAnswer(inv -> {
      Def d = inv.getArgument(0);
      return Integer.parseInt(values.getOrDefault(d.key(), d.fallback()));
    });
    when(rs.getBool(any(Def.class))).thenAnswer(inv -> {
      Def d = inv.getArgument(0);
      return Boolean.parseBoolean(values.getOrDefault(d.key(), d.fallback()));
    });
    return new PaytmSettings(rs);
  }

}
