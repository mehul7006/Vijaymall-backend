package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.pos.infra.PosCatalogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PosCatalogVersionTest {
  private final Instant older = Instant.parse("2026-11-01T10:00:00Z");
  private final Instant newer = Instant.parse("2026-11-05T10:00:00Z");
  private final CatalogService catalog = mock(CatalogService.class);
  private final PosCatalogRepository repo = mock(PosCatalogRepository.class);
  private final PosCatalogService service = new PosCatalogService(catalog, repo, mock(ShopService.class), mock(InventoryService.class),
      mock(AuditService.class), Clock.fixed(older, ZoneOffset.UTC));

  @Test
  void theVersionIsTheLatestChangeToAnyProductOrItsCounterDetails() {
    when(catalog.lastChange()).thenReturn(older);
    when(repo.lastChange()).thenReturn(newer);
    assertThat(service.catalogVersion()).isEqualTo(newer);
    when(catalog.lastChange()).thenReturn(newer);
    when(repo.lastChange()).thenReturn(older);
    assertThat(service.catalogVersion()).isEqualTo(newer);
    when(repo.lastChange()).thenReturn(null);
    assertThat(service.catalogVersion()).isEqualTo(newer);
  }

  @Test
  void anEmptyShopHasTheEarliestVersion() {
    when(catalog.lastChange()).thenReturn(null);
    when(repo.lastChange()).thenReturn(null);
    assertThat(service.catalogVersion()).isEqualTo(Instant.EPOCH);
    when(repo.lastChange()).thenReturn(older);
    assertThat(service.catalogVersion()).isEqualTo(older);
  }
}
