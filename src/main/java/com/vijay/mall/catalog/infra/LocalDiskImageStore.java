package com.vijay.mall.catalog.infra;

import com.vijay.mall.catalog.app.ImageStore;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Image store that writes resized copies (JPEG, or PNG when the picture has transparency) into the folder configured as {@code imagesDir}; the files are served under {@code /img}.
 * <p>Each upload is saved at a maximum side of 400 px and 1000 px under a random UUID name, so the original file name is never used.
 */
@Component
public class LocalDiskImageStore implements ImageStore {
  /** Largest accepted image in pixels (width times height), to protect memory when decoding. */
  private static final long MAX_PIXELS = 25_000_000L;
  /** The path {@link #save} hands out: folder, UUID name, 400 px marker and extension. */
  private static final Pattern SAVED = Pattern.compile("^/img/([a-z0-9-]+)/([0-9a-f-]{36})_400\\.(jpg|png)$");
  /** Side of the larger app icon; the smaller one is {@link #ICON_SMALL}. */
  private static final int ICON_LARGE = 512;
  private static final int ICON_SMALL = 192;
  private final Path root;

  /** Creates the store rooted at the configured images directory (made absolute). */
  public LocalDiskImageStore(AppProps props) { this.root = Path.of(props.imagesDir()).toAbsolutePath(); }

  @Override
  public String save(byte[] content, String folder) {
    try {
      BufferedImage src = ImageIO.read(new ByteArrayInputStream(content));
      if (src == null) throw ApiException.fieldError("UNSUPPORTED_IMAGE", "file", "must be an image (JPEG, PNG or WebP)");
      if ((long) src.getWidth() * src.getHeight() > MAX_PIXELS) {
        throw ApiException.fieldError("FILE_TOO_LARGE", "file", "image is too large (max " + MAX_PIXELS + " pixels)");
      }
      if (!folder.matches("[a-z0-9-]+")) throw new IllegalArgumentException("bad folder");
      String name = UUID.randomUUID().toString();
      Path dir = root.resolve(folder);
      Files.createDirectories(dir);
      boolean alpha = src.getColorModel().hasAlpha();
      String ext = alpha ? "png" : "jpg";
      ImageIO.write(scale(src, 400, alpha), ext, dir.resolve(name + "_400." + ext).toFile());
      ImageIO.write(scale(src, 1000, alpha), ext, dir.resolve(name + "_1000." + ext).toFile());
      return "/img/" + folder + "/" + name + "_400." + ext;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Icons saveIcons(byte[] content, String savedUrl) {
    Matcher m = saved(savedUrl);
    try {
      BufferedImage src = ImageIO.read(new ByteArrayInputStream(content));
      if (src == null) throw ApiException.fieldError("UNSUPPORTED_IMAGE", "file", "must be an image (JPEG, PNG or WebP)");
      return writeIcons(src, m);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Optional<Icons> rebuildIcons(String savedUrl) {
    Matcher m = saved(savedUrl);
    Path big = root.resolve(m.group(1)).resolve(m.group(2) + "_1000." + m.group(3));
    try {
      BufferedImage src = Files.isRegularFile(big) ? ImageIO.read(big.toFile()) : null;
      return src == null ? Optional.empty() : Optional.of(writeIcons(src, m));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void delete(String savedUrl) {
    Matcher m = savedUrl == null ? null : SAVED.matcher(savedUrl);
    if (m == null || !m.matches()) return;
    Path dir = root.resolve(m.group(1));
    String base = m.group(2);
    try {
      for (String file : new String[] {base + "_400." + m.group(3), base + "_1000." + m.group(3), base + "_icon" + ICON_SMALL + ".png",
          base + "_icon" + ICON_LARGE + ".png"}) {
        Files.deleteIfExists(dir.resolve(file));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Matcher saved(String savedUrl) {
    Matcher m = savedUrl == null ? null : SAVED.matcher(savedUrl);
    if (m == null || !m.matches()) throw new IllegalArgumentException("not a saved image path");
    return m;
  }

  private Icons writeIcons(BufferedImage src, Matcher m) throws IOException {
    Path dir = root.resolve(m.group(1));
    Files.createDirectories(dir);
    String base = m.group(2);
    ImageIO.write(squareIcon(src, ICON_LARGE), "png", dir.resolve(base + "_icon" + ICON_LARGE + ".png").toFile());
    ImageIO.write(squareIcon(src, ICON_SMALL), "png", dir.resolve(base + "_icon" + ICON_SMALL + ".png").toFile());
    String prefix = "/img/" + m.group(1) + "/" + base;
    return new Icons(prefix + "_icon" + ICON_SMALL + ".png", prefix + "_icon" + ICON_LARGE + ".png");
  }

  /**
   * Cuts the largest centred square out of the picture and scales it to exactly {@code size} by {@code size} pixels as ARGB, so the
   * transparency of a PNG logo is kept. Big pictures are halved step by step first, which keeps the small result smooth.
   */
  static BufferedImage squareIcon(BufferedImage src, int size) {
    int side = Math.min(src.getWidth(), src.getHeight());
    BufferedImage cur = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
    while (cur.getWidth() / 2 >= size) cur = drawn(cur, cur.getWidth() / 2);
    return drawn(cur, size);
  }

  private static BufferedImage drawn(BufferedImage src, int side) {
    BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(src, 0, 0, side, side, null);
    g.dispose();
    return out;
  }

  /**
   * Shrinks the image so its longer side is at most {@code maxSide} (never enlarges it) and flattens it onto a white background,
   * because JPEG has no transparency (pictures with an alpha channel are saved as PNG and keep it).
   */
  static BufferedImage scale(BufferedImage src, int maxSide) { return scale(src, maxSide, false); }

  /**
   * Same as {@link #scale(BufferedImage, int)}, but when {@code keepAlpha} is set the copy stays ARGB with its transparency
   * (for PNG output) instead of being flattened onto white.
   */
  static BufferedImage scale(BufferedImage src, int maxSide, boolean keepAlpha) {
    int w = src.getWidth(), h = src.getHeight();
    double f = Math.min(1.0, (double) maxSide / Math.max(w, h));
    int nw = Math.max(1, (int) Math.round(w * f)), nh = Math.max(1, (int) Math.round(h * f));
    BufferedImage out = new BufferedImage(nw, nh, keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    if (!keepAlpha) {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, nw, nh);
    }
    g.drawImage(src, 0, 0, nw, nh, null);
    g.dispose();
    return out;
  }
}
