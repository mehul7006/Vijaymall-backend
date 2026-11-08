package com.vijay.mall.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.Category;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.StaffService;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DevDataSeederTest {
  private final CatalogService catalog = mock(CatalogService.class);
  private final WalletService wallet = mock(WalletService.class);
  private final PromotionService promotions = mock(PromotionService.class);

  private final PosCatalogService posCatalog = mock(PosCatalogService.class);
  private final StaffService staff = mock(StaffService.class);



  private final DevDataSeeder seeder = new DevDataSeeder(catalog, wallet, promotions,posCatalog,staff);

  @Test
  void doesNothingWhenTheShopAlreadyHasCategories() {
    when(catalog.categories()).thenReturn(List.of(new Category(UUID.randomUUID(), "x", "x", null, 1)));
    seeder.run();
    verify(catalog, never()).createCategory(anyString(), anyString(), anyInt(), anyBoolean());
    verifyNoInteractions(wallet, promotions);
  }

  @Test
  void seedsCategoriesProductsCoinRuleAndCouponOnAnEmptyDatabase() {
    when(catalog.categories()).thenReturn(List.of());
    when(catalog.createCategory(anyString(), anyString(), anyInt(), anyBoolean()))
        .thenAnswer(i -> new Category(UUID.randomUUID(), i.getArgument(0), i.getArgument(1), null, i.getArgument(2)));
    when(catalog.create(any(), any())).thenAnswer(i -> {
      var product = mock(com.vijay.mall.catalog.domain.AdminProduct.class);
      when(product.id()).thenReturn(UUID.randomUUID());
      return product;
    });
    seeder.run();
    verify(catalog, times(5)).createCategory(anyString(), anyString(), anyInt(), anyBoolean());
    var products = ArgumentCaptor.forClass(CatalogService.ProductInput.class);
    verify(catalog, times(13)).create(products.capture(), any());
    assertThat(products.getAllValues()).extracting(CatalogService.ProductInput::nameEn)
        .contains("Basmati Rice", "Toothpaste", "Potato Chips", "Digestive Biscuits", "Bath Soap");
    assertThat(products.getAllValues()).allSatisfy(p -> assertThat(p.pricePaise()).isLessThanOrEqualTo(p.mrpPaise()));
    verify(wallet).createRule(any(), any());
    verify(promotions).create(any(), any());
  }
}
