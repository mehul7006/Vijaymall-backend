package com.vijay.mall.inventory.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.inventory.infra.LotReportRepository.LotRow;
import com.vijay.mall.inventory.infra.LotRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Admin changes to lots (every one audited) and the daily expiry job. */
@Service
public class LotAdminService {
  private static final Logger log = LoggerFactory.getLogger(LotAdminService.class);
  private static final int JOB_ROWS = 200;

  private final LotRepository lots;
  private final InventoryService inventory;
  private final LotReportService reports;
  private final AuditService audit;
  private final OutboxService outbox;
  private final RuntimeSettings settings;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final TransactionTemplate tx;

  /** Creates the service. */
  public LotAdminService(LotRepository lots, InventoryService inventory, LotReportService reports, AuditService audit, OutboxService outbox,
                         RuntimeSettings settings, ApplicationEventPublisher events, Clock clock, TransactionTemplate tx) {
    this.tx = tx;
    this.lots = lots;
    this.inventory = inventory;
    this.reports = reports;
    this.audit = audit;
    this.outbox = outbox;
    this.settings = settings;
    this.events = events;
    this.clock = clock;
  }

  /** The saved lot switches of a variant ({@code lotPriced}, {@code trackExpiry}); 404 for an unknown variant. */
  @Transactional(readOnly = true)
  public Map<String, Object> settingsOf(UUID variantId) {
    boolean[] f = lots.findFlags(variantId).orElseThrow(() -> ApiException.notFound("Variant"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("variantId", variantId);
    out.put("lotPriced", f[0]);
    out.put("trackExpiry", f[1]);
    return out;
  }

  /**
   * Switches a variant's lot pricing (its selling price and MRP follow the lot that sells next) and expiry tracking (earliest expiry
   * is sold first). Turning lot pricing on takes effect at the next stock movement of the variant.
   */
  @Transactional
  public Map<String, Object> setFlags(UUID actor, UUID variantId, boolean lotPriced, boolean trackExpiry) {
    boolean[] before = lots.flags(variantId);
    if (!lots.setFlags(variantId, lotPriced, trackExpiry, clock.instant())) throw ApiException.notFound("Variant");
    audit.log(actor, "LOT_SETTINGS", "product", variantId, "lotPriced " + before[0] + " -> " + lotPriced + ", trackExpiry " + before[1] + " -> " + trackExpiry);
    events.publishEvent(new CatalogChanged(CatalogChanged.CATALOG, clock.instant()));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("variantId", variantId);
    out.put("lotPriced", lotPriced);
    out.put("trackExpiry", trackExpiry);
    return out;
  }

  /** Takes everything left in one lot out of stock (expired or spoiled goods). Audited. Returns the units written off. */
  @Transactional
  public Map<String, Object> writeOff(UUID actor, UUID lotId, String reason) {
    int units = inventory.writeOffLot(lotId, actor);
    audit.log(actor, "LOT_WRITE_OFF", "stock_lot", lotId, units + " units" + (reason == null || reason.isBlank() ? "" : ": " + reason.strip()));
    return Map.of("lotId", lotId, "unitsWrittenOff", units);
  }

  /**
   * Every day at 03:15 shop time: tells staff about lots that expire within the alert window and, when the admin switched
   * {@code inventory.autoWriteOffExpired} on, writes off lots already past their date.
   */
  @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Kolkata")
  public void dailyExpiryCheck() {
    int days = settings.getInt(Settings.INVENTORY_EXPIRY_ALERT_DAYS);
    int written = 0;
    if (settings.getBool(Settings.INVENTORY_AUTO_WRITE_OFF)) {
      for (LotRow l : reports.expired(JOB_ROWS)) {
        try {
          tx.execute(_ -> writeOff(null, l.id(), "expired " + l.expiryDate()));
          written++;
        } catch (ApiException e) {
          log.warn("Expired lot {} could not be written off: {}", l.id(), e.getMessage());
        }
      }
    }
    List<LotRow> soon = reports.expiringWithin(days, JOB_ROWS);
    int writtenOff = written;
    if (soon.isEmpty() && writtenOff == 0) return;
    List<Map<String, Object>> items = soon.stream().limit(10).map(l -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("productId", l.productId().toString());
      m.put("name", l.productName());
      m.put("qty", l.qtyRemaining());
      m.put("expiryDate", l.expiryDate().toString());
      return m;
    }).toList();
    tx.executeWithoutResult(_ -> outbox.add("LOT_EXPIRY", Map.of("lots", soon.size(), "days", days, "writtenOff", writtenOff, "items", items)));
    log.info("Expiry check lots={} days={} writtenOff={}", soon.size(), days, writtenOff);
  }
}
