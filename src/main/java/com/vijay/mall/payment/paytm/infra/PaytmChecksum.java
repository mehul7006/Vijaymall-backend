package com.vijay.mall.payment.paytm.infra;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Paytm's request checksum, implemented with the JDK only after reading Paytm's open-source checksum library
 * (github.com/paytm/Paytm_JAVA_Checksum, class {@code PaytmChecksum}, and the equivalent Python source).
 *
 * <p>The algorithm: take the string to sign, append {@code "|"} and a random 4-character salt, hash it with SHA-256 (lower-case
 * hex), append the salt to the hex digest, encrypt that text with AES/CBC/PKCS5Padding using the merchant key as the AES key
 * (16 bytes, UTF-8) and the fixed IV {@code @@@@&&&&####$$$$}, and Base64-encode the result. Verifying decrypts the checksum, takes
 * the last 4 characters as the salt and compares with the hash of the string to check.
 *
 * <p>Which string is signed (the JSON {@code body} object exactly as sent) is decided in {@link PaytmEdcClient}, in one place.
 *
 * <p>Status: the algorithm matches Paytm's published source. Interoperability with Paytm's servers is UNVERIFIED until sandbox
 * credentials exist, and Paytm publishes no test vector.
 */
public final class PaytmChecksum {
  private static final byte[] IV = "@@@@&&&&####$$$$".getBytes(StandardCharsets.UTF_8);
  private static final String SALT_ALPHABET = "9876543210ZYXWVUTSRQPONMLKJIHGFEDCBAabcdefghijklmnopqrstuvwxyz!@#$&_";
  private static final int SALT_LENGTH = 4;
  private static final SecureRandom RANDOM = new SecureRandom();

  private PaytmChecksum() {}

  /** Checksum of {@code body} with a fresh random salt, so two calls normally differ. */
  public static String generateSignature(String body, String key) {
    StringBuilder salt = new StringBuilder(SALT_LENGTH);
    for (int i = 0; i < SALT_LENGTH; i++) salt.append(SALT_ALPHABET.charAt(RANDOM.nextInt(SALT_ALPHABET.length())));
    return signWithSalt(body, key, salt.toString());
  }

  /** Checksum with a given salt (for tests that need a fixed value). */
  static String signWithSalt(String body, String key, String salt) {
    return encrypt(hash(body, salt), key);
  }

  /** True when {@code checksum} was made from {@code body} with {@code key}; false for anything unreadable or different. */
  public static boolean verifySignature(String body, String key, String checksum) {
    if (body == null || key == null || checksum == null || checksum.isBlank()) return false;
    try {
      String plain = decrypt(checksum.trim(), key);
      if (plain.length() <= SALT_LENGTH) return false;
      String salt = plain.substring(plain.length() - SALT_LENGTH);
      return MessageDigest.isEqual(plain.getBytes(StandardCharsets.UTF_8), hash(body, salt).getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String hash(String body, String salt) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest((body + "|" + salt).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest) + salt;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String encrypt(String text, String key) {
    return Base64.getEncoder().encodeToString(crypt(Cipher.ENCRYPT_MODE, text.getBytes(StandardCharsets.UTF_8), key));
  }

  private static String decrypt(String base64, String key) {
    return new String(crypt(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(base64), key), StandardCharsets.UTF_8);
  }

  private static byte[] crypt(int mode, byte[] data, String key) {
    try {
      Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
      c.init(mode, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(IV));
      return c.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("checksum failed: " + e.getClass().getSimpleName(), e);
    }
  }
}
