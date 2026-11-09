package com.vijay.mall.common.files;

import java.util.Optional;

/**
 * Port: keeps private documents (invoice scans and the like) that are never served as static files. Unlike the image store nothing is resized
 * or re-encoded, and the files live outside the public image folder, so they can only be read through an authenticated endpoint.
 */
public interface FileStore {
  /**
   * Stores the bytes under a new random name in the folder and returns that name (a UUID plus the kind's extension).
   *
   * @param folder lower case letters, digits and dashes
   */
  String save(String folder, byte[] content, FileKind kind);

  /** The stored bytes; empty when the name is unknown, malformed or the file is gone. */
  Optional<byte[]> read(String folder, String name);

  /** Deletes a stored file; a missing or malformed name is ignored. */
  void delete(String folder, String name);
}
