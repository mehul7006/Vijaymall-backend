package com.vijay.mall.pos.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.WeighedMode;
import org.junit.jupiter.api.Test;

class ShopBodyCoverageTest {
  @Test void shopBody_oldConstructorAndKeepRules() {
    var b = new AdminPosController.ShopBody("  Shop ", null, null, " 24abcde1234f1z5 ", null, null, null, null, null, null, null);
    assertThat(b.details()).isNull();
    ShopSetting kept = b.toSetting("old promise", "old details");
    assertThat(kept.promise()).isEqualTo("old promise");
    assertThat(kept.details()).isEqualTo("old details");
    assertThat(kept.shopName()).isEqualTo("Shop");
    assertThat(kept.gstin()).isEqualTo("24ABCDE1234F1Z5");
    assertThat(kept.weighedMode()).isEqualTo(WeighedMode.WEIGHT_GRAMS);
    // no keep value for details at all
    assertThat(b.toSetting("p").details()).isEmpty();
    var withText = new AdminPosController.ShopBody("S", "a", "p", "", "27", "u@upi", "ab", "f", "3", WeighedMode.PRICE_PAISE,
        " new ");
    assertThat(withText.toSetting("old").promise()).isEqualTo("new");
    var full = new AdminPosController.ShopBody("S", "a", "p", "", "27", "u@upi", "ab", "f", "3", WeighedMode.PRICE_PAISE,
        "pr", " det ");
    assertThat(full.toSetting("old", "olddet").details()).isEqualTo("det");
    assertThat(full.toSetting("old").invoicePrefix()).isEqualTo("AB");
  }

}
