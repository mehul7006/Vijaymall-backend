package com.vijay.mall.identity.infra;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings.Def;
import java.util.HashMap;
import java.util.Map;

/** A RuntimeSettings stand-in backed by a map that tests can change between sends (like an admin save). */
final class FakeSettings {
  final Map<Def, String> values = new HashMap<>();
  final RuntimeSettings settings = mock(RuntimeSettings.class);

  FakeSettings() {
    when(settings.get(any())).thenAnswer(i -> {
      Def d = i.getArgument(0);
      String v = values.get(d);
      return v != null ? v : d.fallback() == null ? "" : d.fallback();
    });
    when(settings.isSet(any())).thenAnswer(i -> !settings.get(i.getArgument(0)).isBlank());
    when(settings.getInt(any())).thenAnswer(i -> Integer.parseInt(settings.get(i.getArgument(0)).trim()));
  }

  FakeSettings with(Def d, String v) { values.put(d, v); return this; }
}
