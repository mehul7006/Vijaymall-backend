package com.vijay.mall.label.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GujaratiRendererTest {
  private final AwtGujaratiRenderer renderer = new AwtGujaratiRenderer();

  @Test void bundledFontLoadsAndDrawsJoinedLetters() {
    assertThat(renderer.available()).isTrue();
    var bmp = renderer.render("બાસમતી ચોખા", 8, false, 203, 40);
    assertThat(bmp.width()).isBetween(20, 40 * 8 + 4);
    assertThat(bmp.height()).isBetween(8, 40);
    long black = 0;
    for (byte b : bmp.gray()) if ((b & 0xFF) < 128) black++;
    assertThat(black).as("ink").isGreaterThan(50);
    assertThat(bmp.gray()).hasSize(bmp.width() * bmp.height());
  }

  @Test void longTextIsShrunkToFitTheWidth() {
    String text = "બાસમતી ચોખા બાસમતી ચોખા બાસમતી ચોખા બાસમતી ચોખા";
    var big = renderer.render(text, 8, false, 203, 500);
    double limitMm = big.width() * 0.75 * 25.4 / 203;
    var small = renderer.render(text, 8, true, 203, limitMm);
    assertThat(small.width()).isLessThan(big.width());
    assertThat(small.width()).isLessThanOrEqualTo((int) (limitMm * 203 / 25.4) + 4);
  }

  @Test void packing_oneBitPerDot_msbFirst_blackIsOneOrZero() {
    var bmp = new GujaratiRenderer.Bitmap(10, 2, new byte[] {0, (byte) 255, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 0,
        (byte) 255, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 0});
    assertThat(bmp.rowBytes()).isEqualTo(2);
    byte[] ink = bmp.packed(true);
    assertThat(ink).hasSize(4);
    assertThat(ink[0] & 0xFF).isEqualTo(0b1010_0000);
    assertThat(ink[1] & 0xFF).isEqualTo(0b0100_0000);
    assertThat(ink[2] & 0xFF).isEqualTo(0b0101_0101);
    byte[] white = bmp.packed(false);
    assertThat(white[0] & 0xFF).isEqualTo(0b0101_1111);
    assertThat(white[1] & 0xFF).isEqualTo(0b1011_1111);
    assertThat(white[3] & 0xFF).isEqualTo(0b1011_1111);
  }
}
