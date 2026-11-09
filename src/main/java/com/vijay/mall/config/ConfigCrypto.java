package com.vijay.mall.config;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts secret settings (payment keys, SMTP password...) before they are stored. AES-256-GCM with a fresh random IV for every
 * value. The key (32 random bytes, base64) comes from the environment (CONFIG_ENCRYPTION_KEY), so a copy of the database alone
 * does not reveal any secret. Without a key no secret can be saved at all.
 */
@Component
public class ConfigCrypto {
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /**
   * Creates the cipher from the base64 key; with a blank key {@link #available()} is false. Fails at start-up when the key is not 32 bytes of base64.
   */
  public ConfigCrypto(@Value("${app.config.encryption-key:}") String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      this.key = null;
    } else {
      byte[] raw;
      try {
        raw = Base64.getDecoder().decode(base64Key.trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("CONFIG_ENCRYPTION_KEY must be base64 text of exactly 32 bytes (openssl rand -base64 32)");
      }
      if (raw.length != 32) throw new IllegalStateException("CONFIG_ENCRYPTION_KEY must decode to exactly 32 bytes (openssl rand -base64 32)");
      this.key = new SecretKeySpec(raw, "AES");
    }
  }

  /** Whether an encryption key is configured. */
  public boolean available() { return key != null; }

  /**
   * Encrypts {@code plain} and returns base64 of IV followed by ciphertext; throws {@code IllegalStateException} without a key.
   */
  public String encrypt(String plain) {
    requireKey();
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not encrypt the setting", e);
    }
  }

  /** Throws IllegalStateException when the text was not produced by this key (wrong key, or damaged). */
  public String decrypt(String stored) {
    requireKey();
    try {
      byte[] all = Base64.getDecoder().decode(stored);
      if (all.length <= IV_BYTES) throw new IllegalStateException("stored secret is too short");
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
      return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("could not decrypt the setting (wrong CONFIG_ENCRYPTION_KEY?)", e);
    }
  }

  private void requireKey() {
    if (key == null) throw new IllegalStateException("CONFIG_ENCRYPTION_KEY is not set");
  }
}
