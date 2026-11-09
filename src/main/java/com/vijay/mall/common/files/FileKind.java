package com.vijay.mall.common.files;

import java.util.Optional;

/** The kinds of document that may be uploaded as a private file, recognised by their first bytes (the file name and the sent type are never trusted). */
public enum FileKind {
  /** A PDF document. */
  PDF("application/pdf", "pdf"),
  /** A JPEG picture. */
  JPEG("image/jpeg", "jpg"),
  /** A PNG picture. */
  PNG("image/png", "png"),
  /** A WebP picture. */
  WEBP("image/webp", "webp");

  private final String contentType;
  private final String extension;

  FileKind(String contentType, String extension) {
    this.contentType = contentType;
    this.extension = extension;
  }

  /** The media type to serve the file with. */
  public String contentType() { return contentType; }

  /** The file extension (without the dot) used for the stored name. */
  public String extension() { return extension; }

  /** The kind with this media type, for a name stored earlier. */
  public static Optional<FileKind> ofContentType(String contentType) {
    for (FileKind k : values()) if (k.contentType.equals(contentType)) return Optional.of(k);
    return Optional.empty();
  }

  /** Recognises the content by its magic bytes: {@code %PDF-}, JPEG {@code FF D8 FF}, PNG signature, or {@code RIFF....WEBP}; empty for anything else. */
  public static Optional<FileKind> detect(byte[] b) {
    if (b == null) return Optional.empty();
    if (starts(b, 0, '%', 'P', 'D', 'F', '-')) return Optional.of(PDF);
    if (starts(b, 0, 0xFF, 0xD8, 0xFF)) return Optional.of(JPEG);
    if (starts(b, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return Optional.of(PNG);
    if (starts(b, 0, 'R', 'I', 'F', 'F') && starts(b, 8, 'W', 'E', 'B', 'P')) return Optional.of(WEBP);
    return Optional.empty();
  }

  private static boolean starts(byte[] b, int at, int... expected) {
    if (b.length < at + expected.length) return false;
    for (int i = 0; i < expected.length; i++) if ((b[at + i] & 0xFF) != expected[i]) return false;
    return true;
  }
}
