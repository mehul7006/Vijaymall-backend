package com.vijay.mall.config;

import java.util.Set;

/** Published after the admin saved settings (and the new values are live). Listeners apply what needs applying, for example log levels. */
public record SettingsChanged(Set<String> keys) {
  /** Whether the setting was among the saved changes. */
  public boolean touches(Settings.Def def) { return keys.contains(def.key()); }
}
