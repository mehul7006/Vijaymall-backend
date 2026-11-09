package com.vijay.mall.common.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * File store on the local disk, in the folder {@code app.files-dir} (environment {@code FILES_DIR}, default {@code ./data/files}). This is not
 * the images folder, so nothing in it is reachable under {@code /img}. Names are random UUIDs and are checked before use, so a name can never
 * point outside its folder.
 */
@Component
public class LocalDiskFileStore implements FileStore {
  private static final Pattern FOLDER = Pattern.compile("[a-z0-9-]+");
  private static final Pattern NAME = Pattern.compile("[0-9a-f-]{36}\\.(pdf|jpg|png|webp)");
  private final Path root;

  /** Creates the store rooted at the configured folder (made absolute). */
  public LocalDiskFileStore(@Value("${app.files-dir:./data/files}") String dir) { this.root = Path.of(dir).toAbsolutePath(); }

  @Override
  public String save(String folder, byte[] content, FileKind kind) {
    if (!FOLDER.matcher(folder).matches()) throw new IllegalArgumentException("bad folder");
    String name = UUID.randomUUID() + "." + kind.extension();
    try {
      Path dir = root.resolve(folder);
      Files.createDirectories(dir);
      Files.write(dir.resolve(name), content);
      return name;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Optional<Path> path(String folder, String name) {
    if (folder == null || name == null || !FOLDER.matcher(folder).matches() || !NAME.matcher(name).matches()) return Optional.empty();
    return Optional.of(root.resolve(folder).resolve(name));
  }

  @Override
  public Optional<byte[]> read(String folder, String name) {
    try {
      Optional<Path> p = path(folder, name);
      return p.isPresent() && Files.isRegularFile(p.get()) ? Optional.of(Files.readAllBytes(p.get())) : Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void delete(String folder, String name) {
    try {
      Optional<Path> p = path(folder, name);
      if (p.isPresent()) Files.deleteIfExists(p.get());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
