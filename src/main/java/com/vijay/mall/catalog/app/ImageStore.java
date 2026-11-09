package com.vijay.mall.catalog.app;

/** Port: stores an uploaded picture and returns the public path of the small version. */
public interface ImageStore {
  /** Saves two sizes (name_400 and name_1000, .jpg, or .png when the picture has transparency) and returns the /img path of the 400 px file. */
  String save(byte[] content, String folder);
}
