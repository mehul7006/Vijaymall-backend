package com.vijay.mall.catalog.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real image bytes, real files in a temp folder. */
class LocalDiskImageStoreTest {
  private static final Pattern PATH = Pattern.compile("/img/([a-z0-9-]+)/([0-9a-f-]{36})_400\\.jpg");

  @TempDir Path dir;

  private LocalDiskImageStore store() { return store(dir); }

  private static LocalDiskImageStore store(Path root) {
    return new LocalDiskImageStore(new AppProps(null, null, null, root.toString(), null, null));
  }

  private static byte[] encode(BufferedImage img, String format) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertThat(ImageIO.write(img, format, out)).isTrue();
    return out.toByteArray();
  }

  private static BufferedImage rgb(int w, int h) { return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); }

  private static BufferedImage read(Path p) throws IOException {
    BufferedImage img = ImageIO.read(p.toFile());
    assertThat(img).as("readable image " + p).isNotNull();
    return img;
  }

  @Test
  void savesTwoJpegSizesAndReturnsThePublicPathOfTheSmallOne() throws IOException {
    String url = store().save(encode(rgb(2400, 1200), "png"), "products");

    Matcher m = PATH.matcher(url);
    assertThat(m.matches()).as(url).isTrue();
    assertThat(m.group(1)).isEqualTo("products");
    Path small = dir.resolve("products").resolve(m.group(2) + "_400.jpg");
    Path big = dir.resolve("products").resolve(m.group(2) + "_1000.jpg");
    assertThat(read(small).getWidth()).isEqualTo(400);
    assertThat(read(small).getHeight()).isEqualTo(200);
    assertThat(read(big).getWidth()).isEqualTo(1000);
    assertThat(read(big).getHeight()).isEqualTo(500);
    // jpeg magic bytes, not a renamed png
    assertThat(Files.readAllBytes(small)).startsWith((byte) 0xFF, (byte) 0xD8);
  }

  @Test
  void smallImagesAreNeverEnlarged() throws IOException {
    String url = store().save(encode(rgb(300, 120), "png"), "banners");
    String id = url.substring(url.lastIndexOf('/') + 1, url.length() - "_400.jpg".length());
    assertThat(read(dir.resolve("banners").resolve(id + "_400.jpg")).getWidth()).isEqualTo(300);
    assertThat(read(dir.resolve("banners").resolve(id + "_1000.jpg")).getHeight()).isEqualTo(120);
  }

  @Test
  void acceptsJpegInput() throws IOException {
    String url = store().save(encode(rgb(800, 800), "jpg"), "products");
    assertThat(PATH.matcher(url).matches()).isTrue();
  }

  @Test
  void transparentPictureStaysPngWithItsAlphaInBothSizes() throws IOException {
    BufferedImage clear = new BufferedImage(1200, 50, BufferedImage.TYPE_INT_ARGB); // fully transparent
    String url = store().save(encode(clear, "png"), "shop");
    assertThat(url).endsWith("_400.png");
    String id = url.substring(url.lastIndexOf('/') + 1, url.length() - "_400.png".length());
    BufferedImage small = read(dir.resolve("shop").resolve(id + "_400.png"));
    BufferedImage big = read(dir.resolve("shop").resolve(id + "_1000.png"));
    assertThat(small.getWidth()).isEqualTo(400);
    assertThat(big.getWidth()).isEqualTo(1000);
    assertThat(small.getColorModel().hasAlpha()).isTrue();
    assertThat((small.getRGB(25, 5) >>> 24)).isZero();
    assertThat(dir.resolve("shop").resolve(id + "_400.jpg")).doesNotExist();
  }

  @Test
  void everyUploadGetsItsOwnFileName() throws IOException {
    byte[] png = encode(rgb(10, 10), "png");
    assertThat(store().save(png, "products")).isNotEqualTo(store().save(png, "products"));
  }

  @Test
  void bytesThatAreNotAnImageAreRejectedAsValidationErrors() {
    assertThatThrownBy(() -> store().save("hello".getBytes(StandardCharsets.UTF_8), "products"))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo("UNSUPPORTED_IMAGE");
          assertThat(e.status().value()).isEqualTo(400);
          assertThat(e.details()).isEqualTo(java.util.Map.of("fields", java.util.Map.of("file", "must be an image (JPEG, PNG or WebP)")));
        });
    assertThatThrownBy(() -> store().save(new byte[0], "products")).isInstanceOf(ApiException.class);
    assertThat(dir.resolve("products")).doesNotExist();
  }

  @Test
  void imageOverTwentyFiveMillionPixelsIsRejected() throws IOException {
    byte[] huge = encode(new BufferedImage(5001, 5001, BufferedImage.TYPE_BYTE_GRAY), "png"); // 25,010,001 px
    assertThatThrownBy(() -> store().save(huge, "products"))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo("FILE_TOO_LARGE");
          assertThat(e.details().toString()).contains("file", "image is too large (max 25000000 pixels)");
        });
    assertThat(dir.resolve("products")).doesNotExist();
  }

  @Test
  void imageOfExactlyTwentyFiveMillionPixelsIsAccepted() throws IOException {
    byte[] limit = encode(new BufferedImage(5000, 5000, BufferedImage.TYPE_BYTE_GRAY), "png");
    String url = store().save(limit, "products");
    assertThat(PATH.matcher(url).matches()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "../escape", "a/b", "Products", "prod ucts", "pr\u00f6ducts", "a.b"})
  void folderNamesOutsideTheAllowedAlphabetAreRefused(String folder) throws IOException {
    byte[] png = encode(rgb(10, 10), "png");
    assertThatThrownBy(() -> store().save(png, folder))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("bad folder");
    try (var files = Files.list(dir)) {
      assertThat(files).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"p", "products", "banner-2", "0-9"})
  void folderNamesInsideTheAllowedAlphabetAreAccepted(String folder) throws IOException {
    String url = store().save(encode(rgb(10, 10), "png"), folder);
    assertThat(url).startsWith("/img/" + folder + "/");
    assertThat(dir.resolve(folder)).isDirectory();
  }

  @Test
  void diskFailuresSurfaceAsUncheckedIoException() throws IOException {
    Path blocker = dir.resolve("blocker");
    Files.writeString(blocker, "i am a file, not a folder");
    byte[] png = encode(rgb(10, 10), "png");
    assertThatThrownBy(() -> store(blocker).save(png, "products")).isInstanceOf(UncheckedIOException.class);
  }

  // ---------- square app icons ----------
  @Test
  void aWideTransparentLogoBecomesTwoSquareTransparentPngIcons() throws IOException {
    BufferedImage logo = new BufferedImage(1200, 600, BufferedImage.TYPE_INT_ARGB);
    for (int x = 300; x < 900; x++) for (int y = 0; y < 600; y++) logo.setRGB(x, y, 0xFFFF0000);
    LocalDiskImageStore store = store();
    String url = store.save(encode(logo, "png"), "shop");

    var icons = store.saveIcons(encode(logo, "png"), url);

    String base = url.replace("_400.png", "");
    assertThat(icons.icon192Url()).isEqualTo(base + "_icon192.png");
    assertThat(icons.icon512Url()).isEqualTo(base + "_icon512.png");
    BufferedImage small = read(dir.resolve(icons.icon192Url().substring("/img/".length())));
    BufferedImage large = read(dir.resolve(icons.icon512Url().substring("/img/".length())));
    assertThat(small.getWidth()).isEqualTo(192);
    assertThat(small.getHeight()).isEqualTo(192);
    assertThat(large.getWidth()).isEqualTo(512);
    assertThat(large.getHeight()).isEqualTo(512);
    assertThat(large.getColorModel().hasAlpha()).isTrue();
    assertThat(large.getRGB(256, 256) >>> 24).as("the centre is opaque").isEqualTo(255);
    assertThat(large.getRGB(256, 256) & 0xFFFFFF).as("the centre is the red part").isEqualTo(0xFF0000);
  }

  @Test
  void aTallOpaqueLogoIsCentreCroppedAndASmallOneIsEnlarged() {
    BufferedImage tall = rgb(100, 300);
    for (int y = 100; y < 200; y++) for (int x = 0; x < 100; x++) tall.setRGB(x, y, 0x00FF00);
    BufferedImage icon = LocalDiskImageStore.squareIcon(tall, 192);
    assertThat(icon.getWidth()).isEqualTo(192);
    assertThat(icon.getHeight()).isEqualTo(192);
    assertThat(icon.getRGB(96, 5) & 0xFFFFFF).as("top of the crop is green, the cut-off parts are gone").isEqualTo(0x00FF00);
    assertThat(icon.getRGB(96, 186) & 0xFFFFFF).isEqualTo(0x00FF00);
  }

  @Test
  void aBigPictureIsHalvedDownToTheIconSize() {
    BufferedImage icon = LocalDiskImageStore.squareIcon(new BufferedImage(3000, 2000, BufferedImage.TYPE_INT_ARGB), 512);
    assertThat(icon.getWidth()).isEqualTo(512);
    assertThat(icon.getRGB(10, 10) >>> 24).as("empty stays transparent").isZero();
  }

  @Test
  void iconsCanBeRebuiltFromTheStoredLargeCopyOrReportNothingWhenItIsGone() throws IOException {
    LocalDiskImageStore store = store();
    String url = store.save(encode(new BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB), "png"), "shop");
    assertThat(store.rebuildIcons(url)).hasValueSatisfying(i -> assertThat(Files.exists(dir.resolve(i.icon512Url().substring(5)))).isTrue());
    Path big = dir.resolve(url.substring(5).replace("_400", "_1000"));
    Files.write(big, "not an image".getBytes(StandardCharsets.UTF_8));
    assertThat(store.rebuildIcons(url)).as("unreadable").isEmpty();
    Files.delete(big);
    assertThat(store.rebuildIcons(url)).as("missing").isEmpty();
  }

  @Test
  void deleteRemovesEveryFileOfAPictureAndIgnoresOtherPaths() throws IOException {
    LocalDiskImageStore store = store();
    byte[] png = encode(new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB), "png");
    String url = store.save(png, "shop");
    store.saveIcons(png, url);
    String keep = store.save(png, "shop");
    Path shop = dir.resolve("shop");
    try (var files = Files.list(shop)) { assertThat(files.count()).isEqualTo(4 + 2); }

    store.delete(url);
    store.delete(null);
    store.delete("/img/shop/../../etc/passwd_400.png");
    store.delete(url);

    try (var files = Files.list(shop)) { assertThat(files.map(f -> f.getFileName().toString())).allMatch(n -> keep.contains(n.substring(0, 36))); }
  }

  @Test
  void iconsRefuseAMalformedPathAndANonImage() {
    LocalDiskImageStore store = store();
    assertThatThrownBy(() -> store.saveIcons(new byte[] {1}, "/img/shop/x.png")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.saveIcons(new byte[] {1}, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.rebuildIcons("bad")).isInstanceOf(IllegalArgumentException.class);
    String ok = "/img/shop/" + java.util.UUID.randomUUID() + "_400.png";
    assertThatThrownBy(() -> store.saveIcons(new byte[] {1, 2}, ok)).isInstanceOf(ApiException.class);
  }
}
