package com.vijay.mall.pos.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.WeighedMode;
import org.junit.jupiter.api.Test;

class ShopBodyCoverageTest {
  private static final ShopSetting STORED = new ShopSetting("Old shop", "Old address", "9876543210", "24ABCDE1234F1Z5", "27",
      "old@upi", "OLD", "Old footer", "3", WeighedMode.PRICE_PAISE, "old promise", "old details", "OS", "જૂની", "સરનામું", "વચન", "વિગત");

  @Test void omittedKeysKeepEveryStoredValue() {
    var b = new AdminPosController.ShopBody("  New shop ", null, null, null, null, null, null, null, null, null, null, null,
        null, "નવી", null, null, null);
    ShopSetting merged = b.toSetting(STORED);
    assertThat(merged.shopName()).isEqualTo("New shop");
    assertThat(merged.shopNameGu()).isEqualTo("નવી");
    assertThat(merged).usingRecursiveComparison().ignoringFields("shopName", "shopNameGu").isEqualTo(STORED);
  }

  @Test void emptyStringClearsTextAndResetsTheThreeCodes() {
    var b = new AdminPosController.ShopBody("S", "", "", "", "", "", "", "", "", null, "", "", "", "", "", "", "");
    ShopSetting merged = b.toSetting(STORED);
    assertThat(merged.address()).isEmpty();
    assertThat(merged.phone()).isEmpty();
    assertThat(merged.gstin()).isEmpty();
    assertThat(merged.upiId()).isEmpty();
    assertThat(merged.receiptFooter()).isEmpty();
    assertThat(merged.promise()).isEmpty();
    assertThat(merged.details()).isEmpty();
    assertThat(merged.stateCode()).isEqualTo("24");
    assertThat(merged.invoicePrefix()).isEqualTo("VSM");
    assertThat(merged.weighedPrefix()).isEqualTo("2");
    assertThat(merged.weighedMode()).isEqualTo(WeighedMode.PRICE_PAISE);
  }

  @Test void sentValuesReplaceStoredOnesAndAreNormalised() {
    var b = new AdminPosController.ShopBody("S", "a", "p", " 24abcde1234f1z5 ", "29", " u@upi ", "ab", "f", "4", WeighedMode.WEIGHT_GRAMS,
        " new ", " det ");
    ShopSetting merged = b.toSetting(STORED);
    assertThat(merged.gstin()).isEqualTo("24ABCDE1234F1Z5");
    assertThat(merged.invoicePrefix()).isEqualTo("AB");
    assertThat(merged.stateCode()).isEqualTo("29");
    assertThat(merged.weighedPrefix()).isEqualTo("4");
    assertThat(merged.weighedMode()).isEqualTo(WeighedMode.WEIGHT_GRAMS);
    assertThat(merged.promise()).isEqualTo("new");
    assertThat(merged.details()).isEqualTo("det");
    assertThat(merged.upiId()).isEqualTo("u@upi");
  }

  @Test void storedNullDetailsBecomeEmpty() {
    ShopSetting noDetails = new ShopSetting("S", "", "", "", "24", "", "VSM", "", "2", WeighedMode.WEIGHT_GRAMS, "p", null);
    var b = new AdminPosController.ShopBody("S", null, null, null, null, null, null, null, null, null, null);
    assertThat(b.toSetting(noDetails).details()).isEmpty();
  }
}
