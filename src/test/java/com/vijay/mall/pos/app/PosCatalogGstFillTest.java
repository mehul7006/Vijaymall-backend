package com.vijay.mall.pos.app;

import static com.vijay.mall.pos.app.PosTestData.NOW;
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
import com.vijay.mall.common.tax.GstRates;
import com.vijay.mall.gst.app.GstService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.pos.app.PosViews.PosAttributes;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.infra.PosCatalogRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Filling a blank GST rate or HSN of a never-configured product from the GST master when the counter attributes are saved. */
class PosCatalogGstFillTest {
  private final UUID id = UUID.randomUUID();
  private final UUID rowId = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();
  private CatalogService catalog;
  private PosCatalogRepository repo;
  private GstService gst;
  private AuditService audit;
  private PosCatalogService service;

  @BeforeEach
  void setUp() {
    catalog = mock(CatalogService.class);
    repo = mock(PosCatalogRepository.class);
    gst = mock(GstService.class);
    audit = mock(AuditService.class);
    service = new PosCatalogService(catalog, repo, mock(ShopService.class), mock(InventoryService.class), audit, Clock.fixed(NOW, ZoneOffset.UTC));
    service.setGst(gst);
    AdminProduct product = mock(AdminProduct.class);
    when(product.nameEn()).thenReturn("Basmati Rice 5 kg");
    when(product.pricePaise()).thenReturn(45000L);
    when(catalog.adminGet(id)).thenReturn(product);
    when(catalog.categoryName(any())).thenReturn(Optional.of("Staples"));
    when(repo.replaceBarcodes(eq(id), any(), any())).thenReturn(Optional.empty());
  }

  private void master(String hsn, String rate, String verification) {
    when(gst.defaultsFor("Basmati Rice 5 kg", "Staples", new BigDecimal("450.00")))
        .thenReturn(Optional.of(new GstService.Defaults(hsn, rate == null ? null : new BigDecimal(rate), verification, rowId, 0.9)));
  }

  private ProductPos saved() {
    ArgumentCaptor<ProductPos> c = ArgumentCaptor.forClass(ProductPos.class);
    verify(repo).save(c.capture(), any());
    return c.getValue();
  }

  private PosAttributes save(String rate, String hsn) {
    return service.save(id, SoldBy.UNIT, null, rate == null ? null : new BigDecimal(rate), hsn, List.of(), actor);
  }

  private void auditText(String contains) {
    verify(audit).log(eq(actor), eq("UPDATE"), eq("product_pos"), eq(id), org.mockito.ArgumentMatchers.contains(contains));
  }

  @Test void blankRateAndHsnOfANewProductComeFromTheMaster() {
    master("1006", "5", "VERIFIED");
    PosAttributes a = save(null, null);
    assertThat(saved().gstPercent()).isEqualByComparingTo("5");
    assertThat(saved().hsn()).isEqualTo("1006");
    assertThat(a.gstSource()).isEqualTo("MASTER");
    assertThat(a.gstVerification()).isEqualTo("VERIFIED");
    auditText("(from master " + rowId + ")");
  }

  @Test void aTypedZeroIsNotBlankAndOnlyTheHsnIsFilled() {
    master("1006", "5", "VERIFY_PRODUCT");
    PosAttributes a = save("0", "");
    assertThat(saved().gstPercent()).isEqualByComparingTo("0");
    assertThat(saved().hsn()).isEqualTo("1006");
    assertThat(a.gstSource()).isEqualTo("USER");
    assertThat(a.gstVerification()).isEqualTo("VERIFY_PRODUCT");
  }

  @Test void aTypedHsnWinsAndOnlyTheRateIsFilled() {
    master("1006", "5", "VERIFIED");
    PosAttributes a = save(null, " 1234 ");
    assertThat(saved().gstPercent()).isEqualByComparingTo("5");
    assertThat(saved().hsn()).isEqualTo("1234");
    assertThat(a.gstSource()).isEqualTo("MASTER");
  }

  @Test void typedValuesNeverAskTheMaster() {
    PosAttributes a = save("18", "1234");
    assertThat(saved().gstPercent()).isEqualByComparingTo("18");
    assertThat(a.gstSource()).isEqualTo("USER");
    assertThat(a.gstVerification()).isNull();
    verify(gst, never()).defaultsFor(any(), any(), any());
  }

  @Test void aStoredRowIsNeverOverwrittenByAMasterGuess() {
    when(repo.find(id)).thenReturn(Optional.of(new ProductPos(id, SoldBy.UNIT, null, GstRates.of(12), "")));
    PosAttributes a = save(null, null);
    assertThat(saved().gstPercent()).isEqualByComparingTo("12");
    assertThat(saved().hsn()).isEmpty();
    assertThat(a.gstSource()).isEqualTo("STORED");
    assertThat(a.gstVerification()).isNull();
    verify(gst, never()).defaultsFor(any(), any(), any());
  }

  @Test void nothingFoundLeavesZeroAndNoHsn() {
    when(gst.defaultsFor(any(), any(), any())).thenReturn(Optional.empty());
    PosAttributes a = save(null, null);
    assertThat(saved().gstPercent()).isEqualByComparingTo("0");
    assertThat(saved().hsn()).isEmpty();
    assertThat(a.gstSource()).isEqualTo("DEFAULT");
    assertThat(a.gstVerification()).isNull();
  }

  @Test void aMasterRateBillingCannotStoreFillsOnlyTheHsn() {
    master("1006", null, "VERIFIED");
    PosAttributes a = save(null, null);
    assertThat(saved().gstPercent()).isEqualByComparingTo("0");
    assertThat(saved().hsn()).isEqualTo("1006");
    assertThat(a.gstSource()).isEqualTo("DEFAULT");
    assertThat(a.gstVerification()).isEqualTo("VERIFIED");
  }

  @Test void withoutAMasterTheDefaultsApply() {
    PosCatalogService plain = new PosCatalogService(catalog, repo, mock(ShopService.class), mock(InventoryService.class), audit, Clock.fixed(NOW, ZoneOffset.UTC));
    PosAttributes a = plain.save(id, SoldBy.UNIT, null, null, null, List.of(), actor);
    assertThat(a.gstSource()).isEqualTo("DEFAULT");
    assertThat(saved().hsn()).isEmpty();
  }

  @Test void aProductWithoutACategoryIsMatchedByNameAlone() {
    when(catalog.categoryName(any())).thenReturn(Optional.empty());
    when(gst.defaultsFor("Basmati Rice 5 kg", null, new BigDecimal("450.00"))).thenReturn(Optional.empty());
    save(null, null);
    verify(gst).defaultsFor("Basmati Rice 5 kg", null, new BigDecimal("450.00"));
  }

  @Test void configuredDelegatesToTheRepository() {
    when(repo.configured(List.of(id))).thenReturn(Set.of(id));
    assertThat(service.configured(List.of(id))).containsExactly(id);
  }
}
