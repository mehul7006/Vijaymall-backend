package com.vijay.mall.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ConfigCryptoTest {
  private final ConfigCrypto crypto = new ConfigCrypto(SettingsFixture.KEY);

  @Test
  void aSecretSurvivesTheRoundTripAndIsNotStoredReadable() {
    String stored = crypto.encrypt("rzp_live_SECRET value with ünïcode");
    assertThat(stored).doesNotContain("SECRET");
    assertThat(crypto.decrypt(stored)).isEqualTo("rzp_live_SECRET value with ünïcode");
  }

  @Test
  void everyEncryptionUsesAFreshIvSoEqualSecretsLookDifferent() {
    assertThat(crypto.encrypt("same")).isNotEqualTo(crypto.encrypt("same"));
  }

  @Test
  void aTamperedOrForeignValueIsRefused() {
    byte[] raw = Base64.getDecoder().decode(crypto.encrypt("hello"));
    raw[raw.length - 1] ^= 1;
    assertThatThrownBy(() -> crypto.decrypt(Base64.getEncoder().encodeToString(raw))).isInstanceOf(IllegalStateException.class);
    String other = new ConfigCrypto(Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes())).encrypt("hello");
    assertThatThrownBy(() -> crypto.decrypt(other)).isInstanceOf(IllegalStateException.class).hasMessageContaining("CONFIG_ENCRYPTION_KEY");
    assertThatThrownBy(() -> crypto.decrypt("not base64 !!")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> crypto.decrypt(Base64.getEncoder().encodeToString(new byte[5]))).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void withoutAKeyNothingCanBeEncryptedOrRead() {
    ConfigCrypto none = new ConfigCrypto("");
    assertThat(none.available()).isFalse();
    assertThat(new ConfigCrypto(null).available()).isFalse();
    assertThat(new ConfigCrypto("   ").available()).isFalse();
    assertThatThrownBy(() -> none.encrypt("x")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> none.decrypt("x")).isInstanceOf(IllegalStateException.class);
    assertThat(crypto.available()).isTrue();
  }

  @Test
  void aBadKeyStopsTheServerFromStarting() {
    assertThatThrownBy(() -> new ConfigCrypto("!!!not-base64!!!")).isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    assertThatThrownBy(() -> new ConfigCrypto(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aKeyTheCipherRefusesIsReportedAsAnEncryptionFailure() throws Exception {
    // A key of an impossible size cannot come from the constructor (it insists on 32 bytes), so it is put in place directly.
    var field = ConfigCrypto.class.getDeclaredField("key");
    field.setAccessible(true);
    field.set(crypto, new SecretKeySpec(new byte[5], "AES"));
    assertThatThrownBy(() -> crypto.encrypt("hello")).isInstanceOf(IllegalStateException.class).hasMessageContaining("could not encrypt")
        .hasCauseInstanceOf(GeneralSecurityException.class);
  }
}
