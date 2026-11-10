package com.vijay.mall.pos.app;

import com.vijay.mall.common.tax.GstRates;
import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.catalog.domain.ProductData;
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

class PosCatalogServiceCoverageTest {
  private final UUID cheap = UUID.randomUUID();
  private CatalogService catalog;
  private PosCatalogRepository repo;
  private ShopService shop;
  private PosCatalogService service;

  private static String ean(String body12) { return body12 + BarcodeParser.checkDigit(body12); }

  @BeforeEach
  void setUp() {
    catalog = mock(CatalogService.class);
    repo = mock(PosCatalogRepository.class);
    shop = mock(ShopService.class);
    InventoryService inventory = mock(InventoryService.class);
    service = new PosCatalogService(catalog, repo, shop, inventory, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    ShopSetting s = PosTestData.shop();
    when(shop.get()).thenReturn(new ShopSetting(s.shopName(), s.address(), s.phone(), s.gstin(), s.stateCode(), s.upiId(),
        s.invoicePrefix(), s.receiptFooter(), "2", WeighedMode.PRICE_PAISE));
    ProductData data = PosTestData.product(cheap, "Chaff", 10, true); // Rs 0.10 a kilo
    when(catalog.posProducts(any())).thenReturn(List.of(data));
    when(repo.forProducts(any())).thenReturn(Map.of(cheap, new ProductPos(cheap, SoldBy.WEIGHT, "123450", GstRates.of(0), "")));
    when(inventory.stocks(any())).thenReturn(Map.of(cheap, new InventoryRepository.Stock(cheap, 1000000, 5)));
    when(repo.barcodesOf(any())).thenReturn(List.of());
  }

  @Test void scan_priceLabelThatWouldBeOverOneHundredKilos_isOutOfRange() {
    when(repo.findByPlu("123450")).thenReturn(Optional.of(new ProductPos(cheap, SoldBy.WEIGHT, "123450", GstRates.of(0), "")));
    assertThatThrownBy(() -> service.scan(ean("212345099999"))) // Rs 999.99 of a Rs 0.10/kg item is 9999 kg
        .isInstanceOf(ApiException.class).hasMessageContaining("out of range");
  }

  @Test void save_weighedItem_acceptsBarcodesThatCannotBeMistakenForScaleLabels() {
    UUID actor = UUID.randomUUID();
    when(catalog.adminGet(cheap)).thenReturn(mock(AdminProduct.class));
    when(repo.findByPlu("123450")).thenReturn(Optional.empty());
    when(repo.find(cheap)).thenReturn(Optional.of(new ProductPos(cheap, SoldBy.WEIGHT, "123450", GstRates.of(0), "")));
    when(repo.replaceBarcodes(eq(cheap), any(), any())).thenReturn(Optional.empty());
    // 13 characters but not the scale prefix, and the scale prefix but not 13 characters
    var saved = service.save(cheap, SoldBy.WEIGHT, "123450", GstRates.of(0), "1", List.of("8901234567890", "2ABC"), actor);
    assertThat(saved.soldBy()).isEqualTo(SoldBy.WEIGHT);
    assertThat(saved.posOnly()).isTrue();
    verify(repo).replaceBarcodes(cheap, List.of("8901234567890", "2ABC"), NOW);
    verify(catalog).setPosOnly(cheap, true);
  }
}
