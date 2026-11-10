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
import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.catalog.domain.Category;
import com.vijay.mall.common.Page;
import com.vijay.mall.payment.app.MoneyAccountService;
import com.vijay.mall.payment.infra.MoneyAccountRepository.Account;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.StaffService;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DevDataSeederTest {
  private final CatalogService catalog = mock(CatalogService.class);
  private final WalletService wallet = mock(WalletService.class);
  private final PromotionService promotions = mock(PromotionService.class);

  private final PosCatalogService posCatalog = mock(PosCatalogService.class);
  private final StaffService staff = mock(StaffService.class);
  private final MoneyAccountService accounts = mock(MoneyAccountService.class);



  private final DevDataSeeder seeder = new DevDataSeeder(catalog, wallet, promotions, posCatalog, staff, accounts);

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
      var product = mock(AdminProduct.class);
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

  @BeforeEach
  void noAccountsYet() {
    when(accounts.list(any(), any(), anyString(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 0, 50, 0));
  }

  @Test
  void createsTheManualUpiAccountAndCardMachineSoTheTillShowsThem() {
    when(catalog.categories()).thenReturn(List.of(new Category(UUID.randomUUID(), "x", "x", null, 1)));
    seeder.run();
    var inputs = ArgumentCaptor.forClass(MoneyAccountService.Input.class);
    verify(accounts, times(2)).create(any(), inputs.capture());
    assertThat(inputs.getAllValues()).extracting(MoneyAccountService.Input::name, MoneyAccountService.Input::kind,
        MoneyAccountService.Input::provider, MoneyAccountService.Input::identifier)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("Counter UPI", "UPI", "manual", ""),
            org.assertj.core.groups.Tuple.tuple("Card machine", "CARD_TERMINAL", "manual", "DEV0001"));
  }

  @Test
  void leavesAccountsThatAlreadyExistAloneSoRunningTwiceIsHarmless() {
    var upi = new Account(UUID.randomUUID(), "counter upi", "UPI", "manual", "", null, null, true, "", java.time.Instant.EPOCH, java.time.Instant.EPOCH);
    var card = new Account(UUID.randomUUID(), "Card machine", "CARD_TERMINAL", "manual", "DEV0001", null, null, true, "", java.time.Instant.EPOCH, java.time.Instant.EPOCH);
    when(accounts.list(any(), any(), org.mockito.ArgumentMatchers.eq("Counter UPI"), anyInt(), anyInt())).thenReturn(new Page<>(List.of(upi), 0, 50, 1));
    when(accounts.list(any(), any(), org.mockito.ArgumentMatchers.eq("Card machine"), anyInt(), anyInt())).thenReturn(new Page<>(List.of(card), 0, 50, 1));
    when(catalog.categories()).thenReturn(List.of(new Category(UUID.randomUUID(), "x", "x", null, 1)));
    seeder.run();
    verify(accounts, never()).create(any(), any());
  }

  @Test
  void isNotARegisteredBeanUnderTheProdOrDefaultProfile() {
    for (String profile : new String[] {"prod", "default"}) {
      try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
        ctx.getEnvironment().setActiveProfiles(profile);
        ctx.register(DevDataSeeder.class);
        ctx.refresh();
        assertThat(ctx.getBeansOfType(DevDataSeeder.class)).as(profile).isEmpty();
      }
    }
    assertThat(DevDataSeeder.class.getAnnotation(org.springframework.context.annotation.Profile.class).value()).containsExactly("dev");
  }
}
