package com.vijay.mall.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.vijay.mall.common.time.ClockConfig;
import com.vijay.mall.common.web.WebConfig;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;

class MiscCommonTest {
  @ParameterizedTest
  @CsvSource({"-5,1", "0,1", "1,1", "20,20", "50,50", "51,50", "9999,50"})
  void pageSizeIsClampedBetweenOneAndFifty(int asked, int expected) {
    assertThat(Page.clampSize(asked)).isEqualTo(expected);
  }

  @Test
  void clockIsUtc() {
    Clock c = new ClockConfig().clock();
    assertThat(c.getZone()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  void imagesAreServedFromTheConfiguredFolder() {
    var props = new AppProps(null, null, null, "./some/images", null, null);
    var registry = mock(ResourceHandlerRegistry.class, RETURNS_DEEP_STUBS);
    new WebConfig(props).addResourceHandlers(registry);
    verify(registry).addResourceHandler("/img/**");
  }
}
