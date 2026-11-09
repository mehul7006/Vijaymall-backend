package com.vijay.mall.label.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Draws Gujarati with Java 2D and the bundled Noto Sans Gujarati font (SIL Open Font License, see
 * {@code fonts/NotoSansGujarati-OFL.txt}). Java's text layout joins the letters correctly. If the font engine is missing on the
 * server (a very small JRE image) {@link #available()} is false and callers answer with a clear 400 instead of garbage.
 */
@Component
public class AwtGujaratiRenderer implements GujaratiRenderer {
  private static final Logger log = LoggerFactory.getLogger(AwtGujaratiRenderer.class);
  private static final String FONT = "/fonts/NotoSansGujarati.ttf";

  private volatile Font font;
  private volatile boolean tried;

  private Font base() {
    if (!tried) {
      synchronized (this) {
        if (!tried) {
          System.setProperty("java.awt.headless", "true");
          try (InputStream in = AwtGujaratiRenderer.class.getResourceAsStream(FONT)) {
            font = Font.createFont(Font.TRUETYPE_FONT, in);
          } catch (IOException | FontFormatException | RuntimeException | Error e) {
            log.warn("Gujarati label font could not be loaded: {}", e.toString());
          }
          tried = true;
        }
      }
    }
    return font;
  }

  @Override public boolean available() { return base() != null; }

  @Override
  public Bitmap render(String text, double fontPt, boolean bold, int dpi, double maxWidthMm) {
    Font f = base();
    if (f == null) throw new IllegalStateException("Gujarati font is not available");
    double px = fontPt * dpi / 72.0;
    FontRenderContext frc = new FontRenderContext(null, true, true);
    Font sized = f.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) px);
    TextLayout layout = new TextLayout(text, sized, frc);
    double maxPx = maxWidthMm * dpi / 25.4;
    if (layout.getAdvance() > maxPx) {
      double factor = Math.max(0.5, maxPx / layout.getAdvance());
      sized = sized.deriveFont((float) (px * factor));
      layout = new TextLayout(text, sized, frc);
    }
    int width = Math.max(1, (int) Math.ceil(layout.getAdvance()) + 2);
    int ascent = (int) Math.ceil(layout.getAscent());
    int height = Math.max(1, ascent + (int) Math.ceil(layout.getDescent() + layout.getLeading()) + 1);
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
    Graphics2D g = image.createGraphics();
    try {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, width, height);
      g.setColor(Color.BLACK);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
      layout.draw(g, 1, ascent);
    } finally {
      g.dispose();
    }
    return new Bitmap(width, height, ((DataBufferByte) image.getRaster().getDataBuffer()).getData().clone());
  }
}
