package com.vijay.mall.common.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The magic-byte check and the private disk store. */
class FileStoreTest {
  @TempDir Path dir;

  private static byte[] webp() {
    byte[] b = new byte[16];
    System.arraycopy(new byte[] {'R', 'I', 'F', 'F'}, 0, b, 0, 4);
    System.arraycopy(new byte[] {'W', 'E', 'B', 'P'}, 0, b, 8, 4);
    return b;
  }

  @Test
  void kindsAreRecognisedByTheirFirstBytes() {
    assertThat(FileKind.detect("%PDF-1.7".getBytes())).contains(FileKind.PDF);
    assertThat(FileKind.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).contains(FileKind.JPEG);
    assertThat(FileKind.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})).contains(FileKind.PNG);
    assertThat(FileKind.detect(webp())).contains(FileKind.WEBP);
    byte[] riffWave = webp();
    riffWave[8] = 'W';
    riffWave[9] = 'A';
    assertThat(FileKind.detect(riffWave)).isEmpty();
    assertThat(FileKind.detect(new byte[] {'%', 'P'})).isEmpty();
    assertThat(FileKind.detect("<html>".getBytes())).isEmpty();
    assertThat(FileKind.detect(null)).isEmpty();
    assertThat(FileKind.ofContentType("image/webp")).contains(FileKind.WEBP);
    assertThat(FileKind.ofContentType("text/html")).isEmpty();
    assertThat(FileKind.PDF.extension()).isEqualTo("pdf");
  }

  @Test
  void aSavedFileIsReadBackAndDeletedAndBadNamesNeverTouchTheDisk() throws IOException {
    var store = new LocalDiskFileStore(dir.toString());
    String name = store.save("invoices", new byte[] {1, 2, 3}, FileKind.PNG);
    assertThat(name).matches("[0-9a-f-]{36}\\.png");
    assertThat(store.read("invoices", name)).hasValueSatisfying(b -> assertThat(b).containsExactly(1, 2, 3));
    assertThat(store.read("invoices", "../" + name)).isEmpty();
    assertThat(store.read("../x", name)).isEmpty();
    assertThat(store.read(null, name)).isEmpty();
    assertThat(store.read("invoices", null)).isEmpty();
    assertThat(store.read("invoices", "00000000-0000-0000-0000-000000000000.pdf")).isEmpty();
    store.delete("invoices", "../" + name);
    assertThat(Files.exists(dir.resolve("invoices").resolve(name))).isTrue();
    store.delete("invoices", name);
    assertThat(Files.exists(dir.resolve("invoices").resolve(name))).isFalse();
    store.delete("invoices", name);
    assertThatThrownBy(() -> store.save("../up", new byte[] {1}, FileKind.PDF)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void diskFailuresSurfaceAsUncheckedErrors() throws IOException {
    Path file = Files.createFile(dir.resolve("not-a-dir"));
    var store = new LocalDiskFileStore(file.toString());
    assertThatThrownBy(() -> store.save("invoices", new byte[] {1}, FileKind.PDF)).isInstanceOf(java.io.UncheckedIOException.class);
    Path folder = Files.createDirectories(dir.resolve("f"));
    String name = "00000000-0000-0000-0000-000000000001.pdf";
    Files.createDirectories(folder.resolve(name).resolve("child"));
    var ok = new LocalDiskFileStore(dir.toString());
    assertThat(ok.read("f", name)).as("a directory is not a file").isEmpty();
    assertThatThrownBy(() -> ok.delete("f", name)).isInstanceOf(java.io.UncheckedIOException.class);
  }
}
