package com.vijay.mall.catalog.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class ImageScaleTest {
  @Test
  void shrinksLongSideKeepingRatio() {
    BufferedImage out = LocalDiskImageStore.scale(new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB), 400);
    assertEquals(400, out.getWidth());
    assertEquals(200, out.getHeight());
  }

  @Test
  void neverEnlarges() {
    BufferedImage out = LocalDiskImageStore.scale(new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB), 400);
    assertEquals(100, out.getWidth());
    assertEquals(50, out.getHeight());
  }

  @Test
  void extremeRatioStaysAtLeastOnePixel() {
    BufferedImage out = LocalDiskImageStore.scale(new BufferedImage(5000, 2, BufferedImage.TYPE_INT_RGB), 400);
    assertEquals(400, out.getWidth());
    assertEquals(1, out.getHeight());
  }
}
