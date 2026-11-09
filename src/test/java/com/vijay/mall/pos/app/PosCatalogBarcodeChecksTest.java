package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository;
import com.vijay.mall.pos.domain.EanVariableMeasure;
import com.vijay.mall.pos.domain.Gtin;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.infra.PosCatalogRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The check-digit rule when an admin adds a barcode, and how a scan treats store barcodes and the variable-measure reader. */
class PosCatalogBarcodeChecksTest {
  private final UUID rice = UUID.randomUUID();
  private final UUID sugar = UUID.randomUUID();
  private CatalogService catalog;
  private PosCatalogRepository repo;
  private PosCatalogService service;

  @BeforeEach
  void setUp() {
    catalog = mock(CatalogService.class);
    repo = mock(PosCatalogRepository.class);
    var shop = mock(ShopService.class);
    var inventory = mock(InventoryService.class);
    service = new PosCatalogService(catalog, repo, shop, inventory, mock(AuditService.class), Clock.fixed(PosTestData.NOW, ZoneOffset.UTC));
    when(shop.get()).thenReturn(PosTestData.shop());
    ProductData riceData = PosTestData.product(rice, "Rice", 5900, true);
    ProductData sugarData = PosTestData.product(sugar, "Sugar", 8000, true);
    when(catalog.posProducts(any())).thenAnswer(inv -> {
      Collection<UUID> ids = inv.getArgument(0);
      return List.of(riceData, sugarData).stream().filter(p -> ids.contains(p.id())).toList();
    });
    when(repo.forProducts(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, 5, ""),
        sugar, new ProductPos(sugar, SoldBy.WEIGHT, "101", 5, "")));
    when(inventory.stocks(any())).thenReturn(Map.of(rice, new InventoryRepository.Stock(rice, 40, 5),
        sugar, new InventoryRepository.Stock(sugar, 25000, 5)));
    when(repo.barcodesOf(any())).thenReturn(List.of());
    when(repo.findByPlu("101")).thenReturn(Optional.of(new ProductPos(sugar, SoldBy.WEIGHT, "101", 5, "")));
  }

  private void save(List<String> codes) { service.save(rice, SoldBy.UNIT, null, 5, "", codes, UUID.randomUUID()); }

  @Test void save_wrongCheckDigitOnAStandardLengthIsRejectedWithAPlainMessage() {
    assertThatThrownBy(() -> save(List.of("4006381333932"))).isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).details().toString()).contains("barcodes"))
        .hasMessageContaining("wrong last digit");
    assertThatThrownBy(() -> save(List.of("96385075"))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> save(List.of("036000291453"))).isInstanceOf(ApiException.class);
  }

  @Test void save_correctDigitsAndOtherLengthsAndTextAreAllowed() {
    when(repo.replaceBarcodes(any(), any(), any())).thenReturn(Optional.empty());
    save(List.of("4006381333931", "96385074", "036000291452", "12345", "ZZ-CODE-128", "12345678901"));
  }

  @Test void scan_aStoreBarcodeThatStartsWithTheScalePrefixIsAnOrdinaryPackCode() {
    String code = Gtin.withCheckDigit("290000000001");
    when(repo.productByBarcode(code)).thenReturn(Optional.of(rice));
    var scan = service.scan(code);
    assertThat(scan.qty()).isEqualTo(1);
    assertThat(scan.product().name()).isEqualTo("Rice");
  }

  @Test void scan_aStoredScalePrefixCodeWithABadCheckDigitIsStillThatProduct() {
    when(repo.productByBarcode("2900000000019")).thenReturn(Optional.of(rice));
    assertThat(service.scan("2900000000019").product().name()).isEqualTo("Rice");
  }

  @Test void scan_aRealWeighedLabelStillWorksWhenNoBarcodeMatches() {
    var scan = service.scan(Gtin.withCheckDigit("200010101500"));
    assertThat(scan.qty()).isEqualTo(1500);
    assertThat(scan.product().name()).isEqualTo("Sugar");
  }

  @Test void scan_variableMeasureReaderIsOnlyUsedWhenSwitchedOn() {
    String label = new EanVariableMeasure(true, "22").generate("101", 2500);
    service.setVariableMeasure(new EanVariableMeasure(false, "22"));
    assertThatThrownBy(() -> service.scan(label)).isInstanceOf(ApiException.class);
    service.setVariableMeasure(new EanVariableMeasure(true, "22"));
    var scan = service.scan(label);
    assertThat(scan.qty()).isEqualTo(2500);
    assertThat(scan.product().name()).isEqualTo("Sugar");
  }

  @Test void scan_withTheReaderOnOrdinaryCodesStillWork() {
    service.setVariableMeasure(new EanVariableMeasure(true, "22"));
    when(repo.productByBarcode("8901234567890")).thenReturn(Optional.of(rice));
    assertThat(service.scan("8901234567890").product().name()).isEqualTo("Rice");
  }

  @Test void barcodesOf_delegates() {
    when(repo.barcodesOf(rice)).thenReturn(List.of("A1B2"));
    assertThat(service.barcodesOf(rice)).containsExactly("A1B2");
  }
}
