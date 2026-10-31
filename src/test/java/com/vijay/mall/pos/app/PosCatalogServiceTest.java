package com.vijay.mall.pos.app;

import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository;
import com.vijay.mall.pos.domain.BarcodeParser;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.WeighedMode;
import com.vijay.mall.pos.infra.PosCatalogRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PosCatalogServiceTest {
  private final UUID rice = UUID.randomUUID();
  private final UUID sugar = UUID.randomUUID();
  private CatalogService catalog;
  private PosCatalogRepository repo;
  private ShopService shop;
  private InventoryService inventory;
  private PosCatalogService service;

  private static String ean(String body12) { return body12 + BarcodeParser.checkDigit(body12); }

  @BeforeEach
  void setUp() {
    catalog = mock(CatalogService.class);
    repo = mock(PosCatalogRepository.class);
    shop = mock(ShopService.class);
    inventory = mock(InventoryService.class);
    service = new PosCatalogService(catalog, repo, shop, inventory, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    when(shop.get()).thenReturn(PosTestData.shop());
    ProductData riceData = PosTestData.product(rice, "Rice", 5900, true);
    ProductData sugarData = PosTestData.product(sugar, "Sugar", 8000, true);
    when(catalog.posProducts(any())).thenAnswer(inv -> {
      java.util.Collection<UUID> ids = inv.getArgument(0);
      return List.of(riceData, sugarData).stream().filter(p -> ids.contains(p.id())).toList();
    });
    when(repo.forProducts(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, 5, "1006"),
        sugar, new ProductPos(sugar, SoldBy.WEIGHT, "101", 5, "1701")));
    when(inventory.stocks(any())).thenReturn(Map.of(rice, new InventoryRepository.Stock(rice, 40, 5),
        sugar, new InventoryRepository.Stock(sugar, 25000, 5)));
    when(repo.barcodesOf(any())).thenReturn(List.of("8901234567890"));
  }

  @Test void scan_packBarcode_givesOnePiece() {
    when(repo.productByBarcode("8901234567890")).thenReturn(Optional.of(rice));
    var scan = service.scan(" 8901234567890 ");
    assertThat(scan.qty()).isEqualTo(1);
    assertThat(scan.linePaise()).isEqualTo(5900);
    assertThat(scan.product().name()).isEqualTo("Rice");
    assertThat(scan.product().qtyOnHand()).isEqualTo(40);
    assertThat(scan.product().barcodes()).containsExactly("8901234567890");
  }

  @Test void scan_unknownBarcode_isNotFound() {
    when(repo.productByBarcode("999999")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.scan("999999")).isInstanceOf(ApiException.class).hasMessageContaining("not found");
  }

  @Test void scan_blank_rejected() {
    assertThatThrownBy(() -> service.scan("  ")).isInstanceOf(ApiException.class);
  }

  @Test void scan_packBarcodeOfWeighedItem_askForWeight() {
    when(repo.productByBarcode("555555")).thenReturn(Optional.of(sugar));
    assertThatThrownBy(() -> service.scan("555555")).hasMessageContaining("weight");
  }

  @Test void scan_inactiveProduct_isNotFound() {
    UUID off = UUID.randomUUID();
    when(repo.productByBarcode("777777")).thenReturn(Optional.of(off));
    assertThatThrownBy(() -> service.scan("777777")).isInstanceOf(ApiException.class);
  }

  @Test void scan_weighedLabel_gramsMode() {
    when(repo.findByPlu("123450")).thenReturn(Optional.of(new ProductPos(sugar, SoldBy.WEIGHT, "123450", 5, "")));
    var scan = service.scan(ean("212345001500"));
    assertThat(scan.qty()).isEqualTo(1500);
    assertThat(scan.linePaise()).isEqualTo(12000);
  }

  @Test void scan_weighedLabel_zeroPaddedItemCodeMatchesShortCode() {
    when(repo.findByPlu("101")).thenReturn(Optional.of(new ProductPos(sugar, SoldBy.WEIGHT, "101", 5, "")));
    assertThat(service.scan(ean("200010101500")).qty()).isEqualTo(1500);
    assertThat(PosCatalogService.normalizePlu("000000")).isEqualTo("0");
  }

  @Test void scan_weighedLabel_priceMode_turnsPriceIntoGrams() {
    ShopSetting s = PosTestData.shop();
    when(shop.get()).thenReturn(new ShopSetting(s.shopName(), s.address(), s.phone(), s.gstin(), s.stateCode(), s.upiId(),
        s.invoicePrefix(), s.receiptFooter(), "2", WeighedMode.PRICE_PAISE));
    when(repo.findByPlu("123450")).thenReturn(Optional.of(new ProductPos(sugar, SoldBy.WEIGHT, "123450", 5, "")));
    var scan = service.scan(ean("212345004000")); // Rs 40.00 of sugar at Rs 80/kg
    assertThat(scan.qty()).isEqualTo(500);
  }

  @Test void scan_weighedLabel_errors() {
    when(repo.findByPlu("123450")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.scan(ean("212345001500"))).hasMessageContaining("not found");
    String bad = ean("212345001500");
    String broken = bad.substring(0, 12) + (bad.charAt(12) == '0' ? '1' : '0');
    assertThatThrownBy(() -> service.scan(broken)).hasMessageContaining("check digit");
    when(repo.findByPlu("123450")).thenReturn(Optional.of(new ProductPos(rice, SoldBy.UNIT, "123450", 5, "")));
    assertThatThrownBy(() -> service.scan(ean("212345001500"))).hasMessageContaining("not a weighed item");
    when(repo.findByPlu("123450")).thenReturn(Optional.of(new ProductPos(sugar, SoldBy.WEIGHT, "123450", 5, "")));
    assertThatThrownBy(() -> service.scan(ean("212345000000"))).hasMessageContaining("out of range");
  }

  @Test void search_wrapsProductsWithStockAndCounterAttributes() {
    when(catalog.posSearch("ri", 0, 20)).thenReturn(new Page<>(List.of(PosTestData.product(rice, "Rice", 5900, true)), 0, 20, 1));
    var page = service.search("ri", 0, 20);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).gstPercent()).isEqualTo(5);
    assertThat(page.items().get(0).posOnly()).isFalse();
  }

  // ----- admin -----
  private static AdminProduct admin() { return mock(AdminProduct.class); }

  @Test void attributes_defaultsForNeverConfiguredProduct() {
    when(catalog.adminGet(rice)).thenReturn(admin());
    when(repo.find(rice)).thenReturn(Optional.empty());
    var a = service.attributes(rice);
    assertThat(a.soldBy()).isEqualTo(SoldBy.UNIT);
    assertThat(a.gstPercent()).isZero();
    assertThat(a.posOnly()).isFalse();
  }

  @Test void attributes_weighedIsPosOnly() {
    when(catalog.adminGet(sugar)).thenReturn(admin());
    when(repo.find(sugar)).thenReturn(Optional.of(new ProductPos(sugar, SoldBy.WEIGHT, "101", 5, "1701")));
    assertThat(service.attributes(sugar).posOnly()).isTrue();
  }

  @Test void save_unitProduct_withBarcodes() {
    when(catalog.adminGet(rice)).thenReturn(admin());
    when(repo.replaceBarcodes(eq(rice), any(), any())).thenReturn(Optional.empty());
    when(repo.find(rice)).thenReturn(Optional.of(new ProductPos(rice, SoldBy.UNIT, null, 12, "1006")));
    service.save(rice, SoldBy.UNIT, " ", 12, " 1006 ", List.of(" 8901234567890 ", "8901234567890", ""), UUID.randomUUID());
    verify(repo).save(new ProductPos(rice, SoldBy.UNIT, null, 12, "1006"), NOW);
    verify(repo).replaceBarcodes(rice, List.of("8901234567890"), NOW);
    verify(catalog).setPosOnly(rice, false);
  }

  @Test void save_weighed_makesItCounterOnly() {
    when(catalog.adminGet(sugar)).thenReturn(admin());
    when(repo.findByPlu("101")).thenReturn(Optional.of(new ProductPos(sugar, SoldBy.WEIGHT, "101", 5, "")));
    when(repo.replaceBarcodes(eq(sugar), any(), any())).thenReturn(Optional.empty());
    service.save(sugar, SoldBy.WEIGHT, "101", 5, null, null, UUID.randomUUID());
    verify(catalog).setPosOnly(sugar, true);
    verify(repo).save(new ProductPos(sugar, SoldBy.WEIGHT, "101", 5, ""), NOW);
  }

  @Test void save_validation() {
    when(catalog.adminGet(any())).thenReturn(admin());
    UUID actor = UUID.randomUUID();
    assertThatThrownBy(() -> service.save(sugar, SoldBy.WEIGHT, null, 5, "", List.of(), actor)).hasMessageContaining("item code");
    assertThatThrownBy(() -> service.save(sugar, SoldBy.WEIGHT, "ab", 5, "", List.of(), actor)).hasMessageContaining("item code");
    assertThatThrownBy(() -> service.save(rice, SoldBy.UNIT, "12ab", 5, "", List.of(), actor)).hasMessageContaining("digits");
    assertThatThrownBy(() -> service.save(rice, SoldBy.UNIT, null, 7, "", List.of(), actor)).hasMessageContaining("18 or 28");
    assertThatThrownBy(() -> service.save(rice, SoldBy.UNIT, null, 5, "", List.of("ab"), actor)).hasMessageContaining("letters");
    assertThatThrownBy(() -> service.save(sugar, SoldBy.WEIGHT, "101", 5, "", List.of("2123456789012"), actor))
        .hasMessageContaining("scale prefix");
    verify(repo, never()).save(any(), any());
  }

  @Test void save_pluTakenByAnotherProduct_isConflict() {
    when(catalog.adminGet(any())).thenReturn(admin());
    when(repo.findByPlu("101")).thenReturn(Optional.of(new ProductPos(sugar, SoldBy.WEIGHT, "101", 5, "")));
    assertThatThrownBy(() -> service.save(rice, SoldBy.UNIT, "101", 5, "", List.of(), UUID.randomUUID()))
        .isInstanceOf(ApiException.class).hasMessageContaining("Item code");
  }

  @Test void save_barcodeTakenByAnotherProduct_isConflict() {
    when(catalog.adminGet(any())).thenReturn(admin());
    when(repo.replaceBarcodes(eq(rice), any(), any())).thenReturn(Optional.of("8901234567890"));
    assertThatThrownBy(() -> service.save(rice, SoldBy.UNIT, null, 5, "", List.of("8901234567890"), UUID.randomUUID()))
        .isInstanceOf(ApiException.class).hasMessageContaining("Barcode");
  }

  @Test void helpers_delegate() {
    when(repo.forProducts(List.of(rice))).thenReturn(Map.of(rice, ProductPos.defaults(rice)));
    assertThat(service.attributesFor(List.of(rice))).containsKey(rice);
    when(catalog.posProducts(List.of(rice))).thenReturn(List.of(PosTestData.product(rice, "Rice", 5900, true)));
    assertThat(service.productsById(List.of(rice))).containsKey(rice);
  }
}
