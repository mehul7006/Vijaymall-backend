package com.vijay.mall.pos.app;

import com.vijay.mall.common.ShopIdentity;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.infra.ShopSettingRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The shop's own details printed on every bill. */
@Service
public class ShopService implements ShopIdentity {
  private static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
  private static final Pattern PREFIX = Pattern.compile("^[A-Z0-9]{2,6}$");

  private final ShopSettingRepository repo;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service. */
  public ShopService(ShopSettingRepository repo, AuditService audit, Clock clock) {
    this.repo = repo;
    this.audit = audit;
    this.clock = clock;
  }

  private AppCaches caches;

  /** Optional (plain unit tests run without it): the shop details are read on every bill and order, so they are cached. */
  @Autowired(required = false)
  void setCaches(AppCaches caches) { this.caches = caches; }

  /** The shop settings, served from the cache when available because every bill and order reads them. */
  public ShopSetting get() {
    return caches == null ? repo.get() : caches.get(AppCaches.Name.SHOP, "1", repo::get);
  }

  /** Tells open app screens when the shop details change; null in plain unit tests. */
  private LiveEvents live;

  /** Optional: tells open app screens about changes (not provided in plain unit tests). */
  @Autowired(required = false)
  void setLive(LiveEvents live) { this.live = live; }

  /** The shop's name, phone, address and number prefix, read from the settings each time so an edit shows at once. */
  @Override
  public Info identity() {
    ShopSetting s = get();
    return new Info(s.shopName(), s.phone(), s.address(), s.promise() == null ? "" : s.promise(),
        s.details() == null ? "" : s.details(), s.invoicePrefix());
  }

  /**
   * Validates and saves the shop settings, audits the change and tells open app screens.
   *
   * @throws ApiException 400 for a missing name, invalid GSTIN, state code, UPI id, invoice prefix, weighed prefix or
   *     too long details text
   */
  @Transactional
  public ShopSetting save(ShopSetting in, UUID actor) {
    if (in.shopName() == null || in.shopName().isBlank()) throw ApiException.validation("shopName", "shop name is required");
    if (!in.gstin().isBlank() && !GSTIN.matcher(in.gstin()).matches()) throw ApiException.validation("gstin", "not a valid GSTIN");
    if (!in.stateCode().matches("\\d{2}")) throw ApiException.validation("stateCode", "2 digit GST state code");
    if (!in.upiId().isBlank() && !in.upiId().matches("^[\\w.\\-]{2,}@[\\w]{2,}$")) throw ApiException.validation("upiId", "not a valid UPI id");
    if (!PREFIX.matcher(in.invoicePrefix()).matches()) throw ApiException.validation("invoicePrefix", "2 to 6 capital letters or digits");
    if (!in.weighedPrefix().matches("\\d{1,2}")) throw ApiException.validation("weighedPrefix", "1 or 2 digits");
    if (in.details() != null && in.details().length() > 500) throw ApiException.validation("details", "at most 500 characters");
    repo.save(in, clock.instant());
    audit.log(actor, "UPDATE", "shop_setting", 1, in.shopName());
    if (live != null) live.area(CatalogChanged.SHOP);
    return repo.get();
  }
}
