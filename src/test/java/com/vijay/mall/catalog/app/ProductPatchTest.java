package com.vijay.mall.catalog.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.common.error.ApiException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests for {@link ProductPatch}: absent keys keep, present keys change, null clears the clearable fields. */
class ProductPatchTest {
  private static final Instant T1 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-11-02T10:00:00Z");
  private final UUID cat = UUID.randomUUID();
  private final AdminProduct base = new AdminProduct(UUID.randomUUID(), cat, "Rice", "ચોખા", "desc", 6000, 5000, "kg", "1 kg",
      null, true, 7, true, true, false, 7, 5, true, T1, T2, UUID.randomUUID(), 0, 0);

  @Test
  void hotWindowAcceptsTheSameForgivingFormsAsOtherRequestDates() {
    CatalogService.ProductInput in = new ProductPatch(Map.of("hotStart", "2026-11-09", "hotEnd", "2026-11-10T10:00:00")).applyTo(base);
    assertThat(in.hotStart()).isEqualTo(Instant.parse("2026-11-08T18:30:00Z"));
    assertThat(in.hotEnd()).isEqualTo(Instant.parse("2026-11-10T04:30:00Z"));
  }

  @Test
  void emptyPatchKeepsEverythingAndStock() {
    CatalogService.ProductInput in = new ProductPatch(Map.of()).applyTo(base);
    assertThat(in.categoryId()).isEqualTo(cat);
    assertThat(in.nameEn()).isEqualTo("Rice");
    assertThat(in.nameGu()).isEqualTo("ચોખા");
    assertThat(in.description()).isEqualTo("desc");
    assertThat(in.mrpPaise()).isEqualTo(6000);
    assertThat(in.pricePaise()).isEqualTo(5000);
    assertThat(in.unit()).isEqualTo("kg");
    assertThat(in.packSize()).isEqualTo("1 kg");
    assertThat(in.active()).isTrue();
    assertThat(in.hotSale()).isTrue();
    assertThat(in.hotStart()).isEqualTo(T1);
    assertThat(in.hotEnd()).isEqualTo(T2);
    assertThat(in.earnsCoins()).isTrue();
    assertThat(in.coinRedeemable()).isFalse();
    assertThat(in.qtyOnHand()).isEqualTo(-1);
    assertThat(in.lowThreshold()).isEqualTo(-1);
    assertThat(new ProductPatch(Map.of()).touchesVariant()).isFalse();
    assertThat(new ProductPatch(Map.of()).variantName()).isNull();
    assertThat(new ProductPatch(Map.of()).variantOrder()).isNull();
  }

  @Test
  void presentKeysChangeAndNullClearsTheWindow() {
    UUID other = UUID.randomUUID();
    Map<String, Object> body = new HashMap<>();
    body.put("categoryId", other.toString());
    body.put("nameEn", "Basmati");
    body.put("nameGu", "બાસમતી");
    body.put("description", null);
    body.put("mrpPaise", 9000);
    body.put("pricePaise", 8000);
    body.put("unit", "g");
    body.put("packSize", "500 g");
    body.put("active", false);
    body.put("hotSale", false);
    body.put("hotStart", null);
    body.put("hotEnd", "2027-01-01T00:00:00Z");
    body.put("earnsCoins", false);
    body.put("coinRedeemable", true);
    body.put("lowThreshold", 9);
    body.put("variantName", "Long");
    body.put("variantOrder", 3);
    ProductPatch patch = new ProductPatch(body);
    CatalogService.ProductInput in = patch.applyTo(base);
    assertThat(in.categoryId()).isEqualTo(other);
    assertThat(in.nameEn()).isEqualTo("Basmati");
    assertThat(in.nameGu()).isEqualTo("બાસમતી");
    assertThat(in.description()).isNull();
    assertThat(in.mrpPaise()).isEqualTo(9000);
    assertThat(in.pricePaise()).isEqualTo(8000);
    assertThat(in.unit()).isEqualTo("g");
    assertThat(in.packSize()).isEqualTo("500 g");
    assertThat(in.active()).isFalse();
    assertThat(in.hotSale()).isFalse();
    assertThat(in.hotStart()).isNull();
    assertThat(in.hotEnd()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    assertThat(in.earnsCoins()).isFalse();
    assertThat(in.coinRedeemable()).isTrue();
    assertThat(in.lowThreshold()).isEqualTo(9);
    assertThat(patch.touchesVariant()).isTrue();
    assertThat(patch.variantName()).isEqualTo("Long");
    assertThat(patch.variantOrder()).isEqualTo(3);
  }

  @Test
  void explicitNullVariantOrderKeepsTheOrder() {
    Map<String, Object> body = new HashMap<>();
    body.put("variantOrder", null);
    assertThat(new ProductPatch(body).variantOrder()).isNull();
    body.put("variantName", null);
    assertThat(new ProductPatch(body).variantName()).isNull();
  }

  @Test
  void stockCannotBePatched() {
    assertThatThrownBy(() -> new ProductPatch(Map.of("qtyOnHand", 5))).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status().value()).isEqualTo(400);
      assertThat(e.getMessage()).contains("stock endpoint");
    });
  }

  @ParameterizedTest
  @CsvSource({"nameEn,5", "nameEn,''", "nameGu,'  '", "categoryId,nope", "mrpPaise,abc", "pricePaise,1.5", "active,yes",
      "hotStart,tomorrow", "lowThreshold,-1", "unit,7", "variantOrder,x"})
  void badValuesAre400(String key, String value) {
    Object v = value;
    if (key.equals("nameEn") && value.equals("5")) v = 5;
    if (key.equals("unit")) v = 7;
    Map<String, Object> body = Map.of(key, v);
    assertThatThrownBy(() -> {
      ProductPatch p = new ProductPatch(body);
      p.applyTo(base);
      p.variantOrder();
    }).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(400));
  }

  @Test
  void nullInRequiredFieldsIs400() {
    for (String key : new String[] {"nameEn", "categoryId", "active", "mrpPaise"}) {
      Map<String, Object> body = new HashMap<>();
      body.put(key, null);
      assertThatThrownBy(() -> new ProductPatch(body).applyTo(base)).isInstanceOf(ApiException.class);
    }
  }

  @Test
  void lowThresholdTooBigIs400() {
    assertThatThrownBy(() -> new ProductPatch(Map.of("lowThreshold", 5_000_000_000L)).applyTo(base)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> new ProductPatch(Map.of("lowThreshold", -1)).applyTo(base)).isInstanceOf(ApiException.class);
  }
}
