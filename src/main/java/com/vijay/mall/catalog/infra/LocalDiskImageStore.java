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
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Image store that writes resized JPEG copies into the folder configured as {@code imagesDir}; the files are served under {@code /img}.
 * <p>Each upload is saved at a maximum side of 400 px and 1000 px under a random UUID name, so the original file name is never used.
 */
@Component
public class LocalDiskImageStore implements ImageStore {
  /** Largest accepted image in pixels (width times height), to protect memory when decoding. */
  private static final long MAX_PIXELS = 25_000_000L;
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
      ImageIO.write(scale(src, 400), "jpg", dir.resolve(name + "_400.jpg").toFile());
      ImageIO.write(scale(src, 1000), "jpg", dir.resolve(name + "_1000.jpg").toFile());
      return "/img/" + folder + "/" + name + "_400.jpg";
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Shrinks the image so its longer side is at most {@code maxSide} (never enlarges it) and flattens it onto a white background,
   * because JPEG has no transparency.
   */
  static BufferedImage scale(BufferedImage src, int maxSide) {
    int w = src.getWidth(), h = src.getHeight();
    double f = Math.min(1.0, (double) maxSide / Math.max(w, h));
    int nw = Math.max(1, (int) Math.round(w * f)), nh = Math.max(1, (int) Math.round(h * f));
    BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, nw, nh);
    g.drawImage(src, 0, 0, nw, nh, null);
    g.dispose();
    return out;
  }
}
