package com.vijay.mall.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.config.SettingsChanged;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/** The admin's "real client address header" setting reaches the rate limiter without a restart. */
class RateLimitFilterSettingsTest {
  @AfterEach
  void reset() { new RateLimitFilter(mock(RateLimiter.class), JsonMapper.builder().build(), 300, ""); }

  private static MockHttpServletRequest request() {
    var r = new MockHttpServletRequest();
    r.setRemoteAddr("10.0.0.5");
    r.addHeader("CF-Connecting-IP", "203.0.113.9, 10.0.0.5");
    return r;
  }

  @Test
  void theSavedSettingIsUsedAndFollowsLaterChanges() {
    var filter = new RateLimitFilter(mock(RateLimiter.class), JsonMapper.builder().build(), 300, "");
    assertThat(RateLimitFilter.clientIp(request())).as("nothing trusted by default").isEqualTo("10.0.0.5");

    RuntimeSettings settings = mock(RuntimeSettings.class);
    when(settings.get(Settings.CLIENT_IP_HEADER)).thenReturn(" CF-Connecting-IP ");
    filter.setSettings(settings);
    assertThat(RateLimitFilter.clientIp(request())).isEqualTo("203.0.113.9");

    when(settings.get(Settings.CLIENT_IP_HEADER)).thenReturn("");
    filter.onSettingsChanged(new SettingsChanged(Set.of("gst.roundOff")));
    assertThat(RateLimitFilter.clientIp(request())).as("an unrelated change does not re-read it").isEqualTo("203.0.113.9");
    filter.onSettingsChanged(new SettingsChanged(Set.of(Settings.CLIENT_IP_HEADER.key())));
    assertThat(RateLimitFilter.clientIp(request())).isEqualTo("10.0.0.5");

    when(settings.get(Settings.CLIENT_IP_HEADER)).thenReturn(null);
    filter.onSettingsChanged(new SettingsChanged(Set.of(Settings.CLIENT_IP_HEADER.key())));
    assertThat(RateLimitFilter.clientIp(request())).isEqualTo("10.0.0.5");
  }

  @Test
  void withoutSettingsNothingChanges() {
    var filter = new RateLimitFilter(mock(RateLimiter.class), JsonMapper.builder().build(), 300, "CF-Connecting-IP");
    filter.onSettingsChanged(new SettingsChanged(Set.of(Settings.CLIENT_IP_HEADER.key())));
    assertThat(RateLimitFilter.clientIp(request())).isEqualTo("203.0.113.9");
  }
}
