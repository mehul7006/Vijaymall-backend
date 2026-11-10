package com.vijay.mall.purchasing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.common.tax.GstRates;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.gst.app.GstService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.purchasing.app.PurchaseService.TaxBasis;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** The rate and HSN fallback order of a purchase line, and the purchase lookup's use of the GST master. */
class PurchaseTaxFillTest {
  private final UUID id = UUID.randomUUID();
  private final UUID rowId = UUID.randomUUID();

  private static GstService.Defaults master(String hsn, String rate, String verification) {
    return new GstService.Defaults(hsn, rate == null ? null : new BigDecimal(rate), verification, UUID.randomUUID(), 0.9);
  }

  private ProductPos stored(String rate, String hsn) {
    return new ProductPos(id, SoldBy.UNIT, null, GstRates.of(rate), hsn);
  }

  // ---------- fallback order ----------
  @Test void theLinesOwnRateAlwaysWinsAndATypedZeroIsNotBlank() {
    TaxBasis b = new TaxBasis(stored("12", "1111"), true, master("2222", "5", "VERIFIED"));
    assertThat(b.rate(new BigDecimal("0"))).isEqualByComparingTo("0");
    assertThat(b.rate(new BigDecimal("18"))).isEqualByComparingTo("18");
    assertThat(new TaxBasis(stored("0", ""), false, master("2222", "5", "VERIFIED")).rate(BigDecimal.ZERO)).isEqualByComparingTo("0");
  }

  @Test void aBlankRateTakesTheStoredRateOfAConfiguredProductBeforeTheMaster() {
    assertThat(new TaxBasis(stored("12", ""), true, master("2222", "5", "VERIFIED")).rate(null)).isEqualByComparingTo("12");
  }

  @Test void aBlankRateOfAnUnconfiguredProductTakesTheMasterThenZero() {
    assertThat(new TaxBasis(ProductPos.defaults(id), false, master("2222", "5", "VERIFIED")).rate(null)).isEqualByComparingTo("5");
    assertThat(new TaxBasis(ProductPos.defaults(id), false, master("2222", null, "VERIFIED")).rate(null)).isEqualByComparingTo("0");
    assertThat(new TaxBasis(ProductPos.defaults(id), false, null).rate(null)).isEqualByComparingTo("0");
  }

  @Test void hsnFallsBackFromTheLineToTheProductToTheMasterToEmpty() {
    GstService.Defaults m = master("2222", "5", "VERIFIED");
    assertThat(new TaxBasis(stored("5", "1111"), true, m).hsn(" 9999 ")).isEqualTo("9999");
    assertThat(new TaxBasis(stored("5", "1111"), true, m).hsn(null)).isEqualTo("1111");
    assertThat(new TaxBasis(stored("5", ""), true, m).hsn("  ")).isEqualTo("2222");
    assertThat(new TaxBasis(stored("5", ""), true, null).hsn("")).isEmpty();
  }

  @Test void onlyAMasterFillFromAVerifyProductRowNeedsConfirmation() {
    TaxBasis open = new TaxBasis(ProductPos.defaults(id), false, master("2222", "5", "VERIFY_PRODUCT"));
    assertThat(open.needsConfirmation(null, null)).isTrue();
    assertThat(open.needsConfirmation(new BigDecimal("18"), "1234")).isFalse();
    assertThat(open.needsConfirmation(new BigDecimal("18"), null)).isTrue();
    assertThat(open.needsConfirmation(null, "1234")).isTrue();
    assertThat(new TaxBasis(ProductPos.defaults(id), false, master("2222", null, "VERIFY_PRODUCT")).needsConfirmation(null, "1234")).isFalse();
    assertThat(new TaxBasis(stored("5", "1111"), true, master("2222", "5", "VERIFY_PRODUCT")).needsConfirmation(null, null)).isFalse();
    assertThat(new TaxBasis(ProductPos.defaults(id), false, master("2222", "5", "VERIFIED")).needsConfirmation(null, null)).isFalse();
    assertThat(new TaxBasis(ProductPos.defaults(id), false, null).needsConfirmation(null, null)).isFalse();
  }

  // ---------- lookup ----------
  private CatalogService catalog;
  private PosCatalogService pos;
  private GstService gst;
  private PurchaseService service;

  @BeforeEach
  void setUp() {
    catalog = mock(CatalogService.class);
    pos = mock(PosCatalogService.class);
    gst = mock(GstService.class);
    service = new PurchaseService(mock(PurchaseRepository.class), mock(InventoryService.class), catalog, mock(AuditService.class),
        mock(SupplierService.class), pos, mock(ShopService.class), mock(TransactionTemplate.class), Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"),
        ZoneOffset.UTC), mock(AdminOps.class), mock(RuntimeSettings.class));
    service.setGst(gst);
    AdminProduct p = mock(AdminProduct.class);
    when(p.nameEn()).thenReturn("Toor Dal");
    when(p.pricePaise()).thenReturn(15000L);
    when(catalog.adminGet(id)).thenReturn(p);
    when(catalog.categoryName(any())).thenReturn(Optional.of("Pulses"));
    when(pos.configured(List.of(id))).thenReturn(Set.of());
    when(pos.attributesFor(List.of(id))).thenReturn(java.util.Map.of(id, ProductPos.defaults(id)));
  }

  @Test void lookupOfAnUnconfiguredProductShowsTheMasterValues() {
    when(gst.defaultsFor("Toor Dal", "Pulses", new BigDecimal("150.00"))).thenReturn(Optional.of(master("0713", "5", "VERIFY_PRODUCT")));
    PurchaseService.Lookup l = service.lookup(id);
    assertThat(l.gstPercent()).isEqualByComparingTo("5");
    assertThat(l.hsn()).isEqualTo("0713");
    assertThat(l.gstSource()).isEqualTo("MASTER");
    assertThat(l.gstVerification()).isEqualTo("VERIFY_PRODUCT");
  }

  @Test void lookupWithNoMasterMatchIsDefault() {
    when(gst.defaultsFor(any(), any(), any())).thenReturn(Optional.empty());
    PurchaseService.Lookup l = service.lookup(id);
    assertThat(l.gstPercent()).isEqualByComparingTo("0");
    assertThat(l.gstSource()).isEqualTo("DEFAULT");
    assertThat(l.gstVerification()).isNull();
  }

  @Test void lookupWithAMasterRateBillingCannotStoreFillsOnlyTheHsn() {
    when(gst.defaultsFor(any(), any(), any())).thenReturn(Optional.of(master("0713", null, "VERIFIED")));
    PurchaseService.Lookup l = service.lookup(id);
    assertThat(l.gstPercent()).isEqualByComparingTo("0");
    assertThat(l.hsn()).isEqualTo("0713");
    assertThat(l.gstSource()).isEqualTo("DEFAULT");
  }

  @Test void lookupOfAConfiguredProductWithoutHsnFillsOnlyTheHsn() {
    when(pos.configured(List.of(id))).thenReturn(Set.of(id));
    when(pos.attributesFor(List.of(id))).thenReturn(java.util.Map.of(id, stored("12", "")));
    when(gst.defaultsFor(any(), any(), any())).thenReturn(Optional.of(master("0713", "5", "VERIFIED")));
    PurchaseService.Lookup l = service.lookup(id);
    assertThat(l.gstPercent()).isEqualByComparingTo("12");
    assertThat(l.hsn()).isEqualTo("0713");
    assertThat(l.gstSource()).isEqualTo("STORED");
    assertThat(l.gstVerification()).isEqualTo("VERIFIED");
  }

  @Test void lookupOfAFullyConfiguredProductNeverAsksTheMaster() {
    when(pos.configured(List.of(id))).thenReturn(Set.of(id));
    when(pos.attributesFor(List.of(id))).thenReturn(java.util.Map.of(id, stored("12", "1111")));
    PurchaseService.Lookup l = service.lookup(id);
    assertThat(l.hsn()).isEqualTo("1111");
    assertThat(l.gstSource()).isEqualTo("STORED");
    verify(gst, never()).defaultsFor(any(), any(), any());
  }

  @Test void lookupWithoutAMasterBeanShowsTheStoredOrDefaultValues() {
    service.setGst(null);
    assertThat(service.lookup(id).gstSource()).isEqualTo("DEFAULT");
    verify(catalog, never()).categoryName(eq(null));
  }
}
