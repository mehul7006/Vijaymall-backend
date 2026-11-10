package com.vijay.mall.pos.app;

import com.vijay.mall.pos.domain.GstRates;
import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.app.QuoteService;
import com.vijay.mall.pos.app.PosViews.Cart;
import com.vijay.mall.pos.app.PosViews.Item;
import com.vijay.mall.pos.domain.PosRecords.HoldRecord;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.infra.InvoiceCounterRepository;
import com.vijay.mall.pos.infra.PosHoldRepository;
import com.vijay.mall.pos.infra.PosSaleRepository;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Parked baskets expire: the nightly clean-up, the expiry shown on each basket and the text of the 100-basket limit. */
class PosHoldExpiryTest {
  private final UUID cashier = UUID.randomUUID();
  private final UUID rice = UUID.randomUUID();
  private PosHoldRepository holds;
  private AuditService audit;
  private SimpleMeterRegistry metrics;
  private PosService service;

  @BeforeEach
  void setUp() {
    PosCatalogService catalog = mock(PosCatalogService.class);
    holds = mock(PosHoldRepository.class);
    audit = mock(AuditService.class);
    metrics = new SimpleMeterRegistry();
    service = new PosService(catalog, mock(QuoteService.class), mock(InventoryService.class), mock(WalletService.class), mock(PromotionService.class),
        mock(UserRepository.class), mock(ShopService.class), mock(PosSaleRepository.class), holds, mock(InvoiceCounterRepository.class), audit,
        JsonMapper.builder().build(), new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC),
        mock(CreditService.class));
    service.setMetrics(metrics);
    when(catalog.attributesFor(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, GstRates.of(5), "1006")));
  }

  private Cart cart() { return new Cart(List.of(new Item(rice, 1)), null, null, null, null); }

  @Test
  void basketsOlderThanTheExpiryAreRemovedWithAnAuditEntryAndACount() {
    when(holds.deleteCreatedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(3);
    assertThat(service.purgeExpiredHolds()).isEqualTo(3);
    verify(audit).log(eq(null), eq("HOLD_EXPIRED"), eq("pos_hold"), eq(null), eq("3 parked basket(s) older than 7 day(s) removed"));
    assertThat(metrics.get("mall.pos.holds.expired").counter().count()).isEqualTo(3.0);
  }

  @Test
  void aRunThatFindsNothingWritesNoAuditEntry() {
    assertThat(service.purgeExpiredHolds()).isZero();
    verify(audit, never()).log(any(), anyString(), anyString(), any(), anyString());
  }

  @Test
  void theCutoffFollowsTheConfiguredDaysAndZeroNeverExpires() {
    service.setHoldExpiryDays(2);
    service.purgeExpiredHolds();
    verify(holds).deleteCreatedBefore(NOW.minus(Duration.ofDays(2)));
    service.setHoldExpiryDays(0);
    assertThat(service.purgeExpiredHolds()).isZero();
    service.setHoldExpiryDays(-4);
    assertThat(service.purgeExpiredHolds()).isZero();
    verify(holds, org.mockito.Mockito.times(1)).deleteCreatedBefore(any(Instant.class));
  }

  @Test
  void aBasketShowsWhenItExpiresOrNullWhenNeverAndTheLimitMessageSaysSo() {
    HoldRecord stored = new HoldRecord(UUID.randomUUID(), cashier, "L", null, "{\"items\":[]}", NOW);
    when(holds.insert(any(), anyString(), any(), anyString(), any())).thenReturn(stored);
    assertThat(service.hold(cashier, "L", cart()).expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    service.setHoldExpiryDays(0);
    assertThat(service.hold(cashier, "L", cart()).expiresAt()).isNull();

    when(holds.count()).thenReturn(100L);
    assertThatThrownBy(() -> service.hold(cashier, "L", cart())).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.getMessage()).isEqualTo("Too many parked baskets: resume or discard some first"));
    service.setHoldExpiryDays(1);
    assertThatThrownBy(() -> service.hold(cashier, "L", cart())).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.getMessage()).endsWith("automatically after 1 day"));
  }
}
