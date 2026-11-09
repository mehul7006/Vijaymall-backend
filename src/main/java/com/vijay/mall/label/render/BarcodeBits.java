package com.vijay.mall.label.render;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import java.util.Map;

/** Turns a barcode payload into its black and white modules with ZXing. No quiet zone is added: the label's margin is the quiet zone. */
public final class BarcodeBits {
  private static final MultiFormatWriter WRITER = new MultiFormatWriter();

  private BarcodeBits() { }

  /**
   * Encodes the payload.
   *
   * @return one row of modules for EAN-13 and Code 128 (95 wide for EAN-13), a square for QR
   * @throws IllegalArgumentException when the payload cannot be encoded in that symbology
   */
  public static BitMatrix encode(BarcodeType type, String payload) {
    BarcodeFormat format = switch (type) {
      case EAN13 -> BarcodeFormat.EAN_13;
      case CODE128 -> BarcodeFormat.CODE_128;
      case QR -> BarcodeFormat.QR_CODE;
    };
    Map<EncodeHintType, Object> hints = Map.of(EncodeHintType.MARGIN, 0, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
    try {
      return WRITER.encode(payload, format, 1, 1, hints);
    } catch (WriterException e) {
      throw new IllegalArgumentException("cannot encode " + payload + " as " + type + ": " + e.getMessage(), e);
    }
  }
}
