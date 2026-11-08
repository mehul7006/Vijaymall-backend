package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.pos.api.AdminPosController;
import com.vijay.mall.pos.api.PublicShopController;
import com.vijay.mall.pos.domain.BillCalculator;
import com.vijay.mall.pos.domain.PosRecords.ReturnRecord;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.WeighedMode;
import com.vijay.mall.pos.infra.ShopSettingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ShopAndRecordsCoverageTest {
  private static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z");

  private static ShopSetting setting(String name, String promise, String details) {
    return new ShopSetting(name, "Road", "999", "", "24", "", "VSM", "Thanks", "2", WeighedMode.WEIGHT_GRAMS, promise, details);
  }

  @Test void identity_nullPromiseAndDetailsBecomeEmpty() {
    ShopSettingRepository repo = mock(ShopSettingRepository.class);
    when(repo.get()).thenReturn(setting("Shop", null, null));
    var info = new ShopService(repo, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC)).identity();
    assertThat(info.promise()).isEmpty();
    assertThat(info.details()).isEmpty();
    assertThat(info.name()).isEqualTo("Shop");
  }

  @Test void identity_keepsPromiseAndDetails() {
    ShopSettingRepository repo = mock(ShopSettingRepository.class);
    when(repo.get()).thenReturn(setting("Shop", "Fresh", "About us"));
    var info = new ShopService(repo, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC)).identity();
    assertThat(info.promise()).isEqualTo("Fresh");
    assertThat(info.details()).isEqualTo("About us");
  }

  @Test void save_nullName_rejected_andNothingStored() {
    ShopSettingRepository repo = mock(ShopSettingRepository.class);
    var service = new ShopService(repo, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> service.save(setting(null, "", ""), UUID.randomUUID())).isInstanceOf(ApiException.class)
        .hasMessageContaining("shop name");
    verify(repo, never()).save(any(), any());
  }

  @Test void save_detailsNullAccepted_longDetailsRejected() {
    ShopSettingRepository repo = mock(ShopSettingRepository.class);
    LiveEvents live = mock(LiveEvents.class);
    var service = new ShopService(repo, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    service.setLive(live);
    ShopSetting ok = setting("Shop", "", null);
    when(repo.get()).thenReturn(ok);
    assertThat(service.save(ok, UUID.randomUUID())).isSameAs(ok);
    verify(repo).save(ok, NOW);
    verify(live).area(CatalogChanged.SHOP);
    assertThatThrownBy(() -> service.save(setting("Shop", "", "x".repeat(501)), UUID.randomUUID()))
        .hasMessageContaining("500");
    service.save(setting("Shop", "", "x".repeat(500)), UUID.randomUUID()); // the limit itself is allowed
  }

  @Test void publicShop_nullDetailsBecomeEmpty_andOldConstructorHasNoDetails() throws Exception {
    ShopService shop = mock(ShopService.class);
    when(shop.get()).thenReturn(setting("Shop", null, null));
    MockMvcBuilders.standaloneSetup(new PublicShopController(shop)).build().perform(get("/api/v1/shop-settings"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.details").value("")).andExpect(jsonPath("$.promise").value(""));
    when(shop.get()).thenReturn(setting("Shop", "P", "D"));
    MockMvcBuilders.standaloneSetup(new PublicShopController(shop)).build().perform(get("/api/v1/shop-settings"))
        .andExpect(jsonPath("$.details").value("D")).andExpect(jsonPath("$.promise").value("P"));
    var old = new PublicShopController.PublicShop("S", "1", "A", "P");
    assertThat(old.details()).isEmpty();
    assertThat(old.promise()).isEqualTo("P");
  }

  @Test void returnRecord_withoutCreditAdjustmentDefaultsToZero() {
    var r = new ReturnRecord(UUID.randomUUID(), "CN/1", UUID.randomUUID(), UUID.randomUUID(), "damaged", 100, 90, 5, 5, 1, 2,
        NOW, List.of(), List.of());
    assertThat(r.creditAdjustedPaise()).isZero();
    assertThat(r.coinsRestored()).isEqualTo(2);
  }

  @Test void billCalculator_couponPerLineMustMatchLineCount() {
    var draft = new BillCalculator.Draft(UUID.randomUUID(), "A", SoldBy.UNIT, 1, 1000, 5, "1");
    assertThatThrownBy(() -> BillCalculator.build(List.of(draft), new long[] {1, 2}, 0))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one coupon amount per line");
    var ok = BillCalculator.build(List.of(draft), new long[] {100}, 0);
    assertThat(ok.totalPaise()).isEqualTo(900);
  }
}
