package com.vijay.mall.inventory.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.inventory.api.AdminLotController;
import com.vijay.mall.inventory.infra.LotReportRepository;
import com.vijay.mall.inventory.infra.LotReportRepository.LotRow;
import com.vijay.mall.inventory.infra.LotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

class LotAdminAndReportsTest {
  private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  private LotRepository lots;
  private InventoryService inventory;
  private LotReportRepository reportRepo;
  private LotReportService reports;
  private OutboxService outbox;
  private RuntimeSettings settings;
  private TransactionTemplate tx;
  private LotAdminService admin;
  private final UUID p = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    lots = mock(LotRepository.class);
    inventory = mock(InventoryService.class);
    reportRepo = mock(LotReportRepository.class);
    outbox = mock(OutboxService.class);
    settings = mock(RuntimeSettings.class);
    tx = mock(TransactionTemplate.class);
    when(tx.execute(any())).thenAnswer(i -> ((org.springframework.transaction.support.TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
    org.mockito.Mockito.doAnswer(i -> {
      ((Consumer<org.springframework.transaction.TransactionStatus>) i.getArgument(0)).accept(null);
      return null;
    }).when(tx).executeWithoutResult(any());
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    reports = new LotReportService(reportRepo, clock);
    admin = new LotAdminService(lots, inventory, reports, mock(AuditService.class), outbox, settings, mock(ApplicationEventPublisher.class), clock, tx);
    when(settings.getInt(Settings.INVENTORY_EXPIRY_ALERT_DAYS)).thenReturn(30);
  }

  private LotRow row(String expiry) {
    return new LotRow(UUID.randomUUID(), p, "Milk", NOW, 10, 4, BigDecimal.ONE, true, null, null, "", LocalDate.parse(expiry), "PURCHASE", null, null, null, null);
  }

  @Test
  void flagsAndWriteOffAreAudited() {
    when(lots.flags(p)).thenReturn(new boolean[] {false, false});
    when(lots.setFlags(eq(p), eq(true), eq(false), any())).thenReturn(true);
    assertThat(admin.setFlags(null, p, true, false)).containsEntry("lotPriced", true);
    when(lots.setFlags(eq(p), eq(false), eq(false), any())).thenReturn(false);
    assertThatThrownBy(() -> admin.setFlags(null, p, false, false)).isInstanceOf(ApiException.class);
    when(inventory.writeOffLot(any(), any())).thenReturn(4);
    assertThat(admin.writeOff(null, p, " spoiled ")).containsEntry("unitsWrittenOff", 4);
    admin.writeOff(null, p, null);
    admin.writeOff(null, p, " ");
  }

  @Test
  void theDailyJobAlertsStaffAndOnlyWritesOffWhenSwitchedOn() {
    when(settings.getBool(Settings.INVENTORY_AUTO_WRITE_OFF)).thenReturn(false);
    admin.dailyExpiryCheck();
    verify(outbox, never()).add(any(), any());
    when(reportRepo.expiring(any(), anyInt())).thenReturn(List.of(row("2026-11-20")));
    admin.dailyExpiryCheck();
    verify(outbox).add(eq("LOT_EXPIRY"), any());
    verify(inventory, never()).writeOffLot(any(), any());

    when(settings.getBool(Settings.INVENTORY_AUTO_WRITE_OFF)).thenReturn(true);
    LotRow dead = row("2026-10-01");
    LotRow gone = row("2026-10-02");
    when(reportRepo.expired(any(), anyInt())).thenReturn(List.of(dead, gone));
    when(inventory.writeOffLot(dead.id(), null)).thenReturn(4);
    when(inventory.writeOffLot(gone.id(), null)).thenThrow(ApiException.notFound("Lot"));
    when(reportRepo.expiring(any(), anyInt())).thenReturn(List.of());
    admin.dailyExpiryCheck();
    verify(outbox, org.mockito.Mockito.times(2)).add(eq("LOT_EXPIRY"), any());
  }

  @Test
  void reportsValidateTheirInputsAndBoundTheirLists() {
    when(reportRepo.expiring(any(), anyInt())).thenReturn(List.of(row("2026-11-09")));
    assertThat(reports.expiring(10).days()).isEqualTo(10);
    assertThat(reports.expiring(null).days()).isEqualTo(30);
    assertThatThrownBy(() -> reports.expiring(-1)).isInstanceOf(ApiException.class);
    LotReportService plain = new LotReportService(reportRepo, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(plain.expiring(null).days()).isEqualTo(30);
    when(reportRepo.expiring(any(), anyInt())).thenReturn(java.util.Collections.nCopies(201, row("2026-11-09")));
    assertThat(reports.expiring(5)).satisfies(e -> {
      assertThat(e.truncated()).isTrue();
      assertThat(e.lots()).hasSize(200);
    });
    when(reportRepo.drift(anyInt())).thenReturn(java.util.Collections.nCopies(201, new LotReportRepository.Drift(p, "x", 1, 0)));
    assertThat(reports.lotCheck().truncated()).isTrue();
    when(reportRepo.drift(anyInt())).thenReturn(List.of());
    assertThat(reports.lotCheck().drifts()).isEmpty();

    assertThatThrownBy(() -> reports.valuation("bad", 0, 10)).isInstanceOf(ApiException.class);
    reports.valuation(null, -1, 10);
    reports.valuation("Category", 0, 10);
    reports.lots(p, true, 0, 10);
    assertThatThrownBy(() -> reports.margin("bad", null, null, 0, 10)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> reports.margin(null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), 0, 10)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> reports.margin(null, LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1), 0, 10)).isInstanceOf(ApiException.class);
    reports.margin("DAY", null, null, 0, 10);
    reports.margin("product", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 0, 10);
    assertThat(reports.shopToday()).isEqualTo(LocalDate.of(2026, 11, 8));
  }

  @Test
  void theControllerDelegates() {
    var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none").subject(UUID.randomUUID().toString()).build();
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
    AdminLotController c = new AdminLotController(reports, admin);
    c.lots(p, false, 0, 20);
    c.expiring(7);
    c.valuation("product", 0, 20);
    c.lotCheck();
    c.margin("product", null, null, 0, 20);
    when(lots.flags(p)).thenReturn(new boolean[] {false, false});
    when(lots.setFlags(eq(p), eq(true), eq(true), any())).thenReturn(true);
    c.lotSettings(p, new AdminLotController.LotSettingsBody(true, true));
    when(inventory.writeOffLot(any(), any())).thenReturn(1);
    c.writeOff(p, new AdminLotController.WriteOffBody("x"));
    c.writeOff(p, null);
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }
}
