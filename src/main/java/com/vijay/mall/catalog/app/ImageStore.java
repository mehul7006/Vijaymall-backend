package com.vijay.mall.catalog.app;

import java.util.Optional;

/** Port: stores an uploaded picture and returns the public path of the small version. */
public interface ImageStore {
  /** The two square PNG app icons made from a picture: 192 px and 512 px (public {@code /img} paths). */
  record Icons(String icon192Url, String icon512Url) {}

  /** Saves two sizes (name_400 and name_1000, .jpg, or .png when the picture has transparency) and returns the /img path of the 400 px file. */
  String save(byte[] content, String folder);

  /**
   * Makes the square PNG app icons (centre-cropped, transparency kept) for a picture saved earlier by {@link #save}, named after it
   * ({@code name_icon192.png}, {@code name_icon512.png}), and returns their paths.
   */
  Icons saveIcons(byte[] content, String savedUrl);

  /**
   * Makes the icons again from the stored large copy of an earlier {@link #save}; empty when that file is no longer on disk or
   * cannot be read as a picture.
   */
  Optional<Icons> rebuildIcons(String savedUrl);

  /** Deletes every file made for a picture saved earlier (both sizes and the icons); unknown or malformed paths are ignored. */
  void delete(String savedUrl);
}
