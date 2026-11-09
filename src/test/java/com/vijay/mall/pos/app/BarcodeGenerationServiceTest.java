package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.domain.Gtin;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.WeighedMode;
import com.vijay.mall.pos.infra.BarcodeSequenceRepository;
import com.vijay.mall.pos.infra.PosCatalogRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BarcodeGenerationServiceTest {
  private final UUID variant = UUID.randomUUID();
  private CatalogService catalog;
  private PosCatalogRepository repo;
  private BarcodeSequenceRepository sequences;
  private ShopService shop;
  private AuditService audit;
  private BarcodeGenerationService service;

  @BeforeEach
  void setUp() {
    catalog = mock(CatalogService.class);
    repo = mock(PosCatalogRepository.class);
    sequences = mock(BarcodeSequenceRepository.class);
    shop = mock(ShopService.class);
    audit = mock(AuditService.class);
    when(shop.get()).thenReturn(PosTestData.shop());
    when(repo.find(any())).thenReturn(Optional.empty());
    when(repo.barcodesOf(variant)).thenReturn(List.of());
    when(repo.addBarcode(eq(variant), anyString(), any())).thenReturn(true);
    when(sequences.prefixes()).thenReturn(List.of());
    service = new BarcodeGenerationService(catalog, repo, sequences, shop, new BarcodeProps("29", new BarcodeProps.VariableMeasure(false, "22")),
        audit, Clock.fixed(PosTestData.NOW, ZoneOffset.UTC));
  }

  @Test void generate_makesAValidEan13WithThePrefixAndSavesIt() {
    when(sequences.next("29")).thenReturn(1L);
    var out = service.generate(variant, UUID.randomUUID());
    assertThat(out.created()).isTrue();
    assertThat(out.barcode()).isEqualTo("2900000000018").hasSize(13);
    assertThat(Gtin.isValid(out.barcode())).isTrue();
    verify(repo).addBarcode(eq(variant), eq("2900000000018"), any());
    verify(audit).log(any(), eq("CREATE"), eq("product_barcode"), eq(variant), eq("generated 2900000000018"));
  }

  @Test void generate_isIdempotent_returnsTheExistingStoreBarcode() {
    when(repo.barcodesOf(variant)).thenReturn(List.of("8901234567890", "2900000000018"));
    when(sequences.prefixes()).thenReturn(List.of("29"));
    var out = service.generate(variant, null);
    assertThat(out.created()).isFalse();
    assertThat(out.barcode()).isEqualTo("2900000000018");
    verify(sequences, never()).next(anyString());
    verify(repo, never()).addBarcode(any(), anyString(), any());
  }

  @Test void generate_aBarcodeUnderAnEarlierPrefixStillCounts() {
    when(repo.barcodesOf(variant)).thenReturn(List.of(Gtin.withCheckDigit("280000000001")));
    when(sequences.prefixes()).thenReturn(List.of("28", "29"));
    assertThat(service.generate(variant, null).barcode()).isEqualTo(Gtin.withCheckDigit("280000000001"));
  }

  @Test void generate_aForeignOrBadBarcodeIsNotAStoreBarcode() {
    when(repo.barcodesOf(variant)).thenReturn(List.of("8901234567890", "2900000000010", "ABC"));
    when(sequences.prefixes()).thenReturn(List.of("29"));
    when(sequences.next("29")).thenReturn(5L);
    assertThat(service.generate(variant, null).created()).isTrue();
  }

  @Test void generate_skipsNumbersThatAreTaken() {
    when(sequences.next("29")).thenReturn(1L, 2L);
    when(repo.addBarcode(eq(variant), eq("2900000000018"), any())).thenReturn(false);
    when(repo.addBarcode(eq(variant), eq("2900000000025"), any())).thenReturn(true);
    assertThat(service.generate(variant, null).barcode()).isEqualTo("2900000000025");
  }

  @Test void generate_skipsACodeThatWouldBeReadAsAWeighedLabelOfAnExistingItemCode() {
    var scale = new BarcodeGenerationService(catalog, repo, sequences, shop, new BarcodeProps("2", new BarcodeProps.VariableMeasure(false, "22")),
        audit, Clock.fixed(PosTestData.NOW, ZoneOffset.UTC));
    when(sequences.next("2")).thenReturn(1L, 1_000_000L);
    when(repo.findByPlu("0")).thenReturn(Optional.of(new ProductPos(UUID.randomUUID(), SoldBy.WEIGHT, "0", 5, "")));
    when(repo.findByPlu("10")).thenReturn(Optional.empty());
    var out = scale.generate(variant, null);
    assertThat(out.barcode()).isEqualTo(Gtin.withCheckDigit("200001000000"));
    verify(repo, never()).addBarcode(any(), eq(Gtin.withCheckDigit("200000000001")), any());
  }

  @Test void generate_aScalePrefixCodeWithAFreeItemCodeIsFine() {
    var scale = new BarcodeGenerationService(catalog, repo, sequences, shop, new BarcodeProps("2", new BarcodeProps.VariableMeasure(false, "22")),
        audit, Clock.fixed(PosTestData.NOW, ZoneOffset.UTC));
    when(sequences.next("2")).thenReturn(1L);
    when(repo.findByPlu(anyString())).thenReturn(Optional.empty());
    assertThat(scale.generate(variant, null).barcode()).startsWith("2");
  }

  @Test void generate_ifTheShopHasNoScalePrefixNothingIsSkipped() {
    when(shop.get()).thenReturn(new ShopSetting("S", "", "", "", "24", "", "VSM", "", "", WeighedMode.WEIGHT_GRAMS));
    when(sequences.next("29")).thenReturn(1L);
    assertThat(service.generate(variant, null).created()).isTrue();
  }

  @Test void generate_weighedItemsAreRefused() {
    when(repo.find(variant)).thenReturn(Optional.of(new ProductPos(variant, SoldBy.WEIGHT, "101", 5, "")));
    assertThatThrownBy(() -> service.generate(variant, null)).isInstanceOf(ApiException.class).hasMessageContaining("item code");
  }

  @Test void generate_unknownVariantIs404() {
    when(catalog.adminGet(variant)).thenThrow(ApiException.notFound("Product"));
    assertThatThrownBy(() -> service.generate(variant, null)).isInstanceOf(ApiException.class);
  }

  @Test void generate_rangeFullWhenNoNumberFitsOrAllAreTaken() {
    when(sequences.next("29")).thenReturn(1_000_000_000_000L);
    assertThatThrownBy(() -> service.generate(variant, null)).isInstanceOf(ApiException.class).hasMessageContaining("No free store barcode");
    when(sequences.next("29")).thenReturn(1L);
    when(repo.addBarcode(eq(variant), anyString(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.generate(variant, null)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("BARCODE_RANGE_FULL");
  }

  @Test void props_storePrefixMustBeInTheTwentiesRange() {
    assertThat(new BarcodeProps("2", null).storePrefix()).isEqualTo("2");
    assertThat(new BarcodeProps("2345", null).storePrefix()).isEqualTo("2345");
    assertThatThrownBy(() -> new BarcodeProps("3", null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BarcodeProps("23456", null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BarcodeProps(null, null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void config_buildsTheVariableMeasureReaderFromTheSetting() {
    var reader = new BarcodeConfig().variableMeasureBarcode(new BarcodeProps("29", new BarcodeProps.VariableMeasure(true, "23")));
    assertThat(reader.enabled()).isTrue();
    assertThat(reader.generate("1", 1)).startsWith("23");
  }
}
