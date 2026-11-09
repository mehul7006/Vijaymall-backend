package com.vijay.mall.common.cache;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.config.RuntimeSettings;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin only (everything under /admin): see what is cached and force fresh data ("hard refresh"). */
@RestController
@RequestMapping("/api/v1/admin/cache")
public class AdminCacheController {
  private static final Logger log = LoggerFactory.getLogger(AdminCacheController.class);

  private final AppCaches caches;
  private final RuntimeSettings settings;
  private final AuditService audit;

  /** Creates the controller. */
  public AdminCacheController(AppCaches caches, RuntimeSettings settings, AuditService audit) {
    this.caches = caches;
    this.settings = settings;
    this.audit = audit;
  }

  /** Every cache with how long it keeps data, how much it holds and how often it saved a database read. */
  @GetMapping
  public List<AppCaches.Stat> stats() { return caches.stats(); }

  /** Empties every cache and reloads the admin settings, so the next request reads the latest data. */
  @PostMapping("/refresh")
  public List<AppCaches.Stat> refreshAll() {
    caches.invalidateAll();
    settings.refresh();
    audit.log(CurrentUser.id(), "CACHE_REFRESH", "cache", "all", null);
    log.atInfo().setMessage("Admin refreshed all caches").addKeyValue("event", "cache-refresh").addKeyValue("cache", "all")
        .addKeyValue("actor", String.valueOf(CurrentUser.id())).log();
    return caches.stats();
  }

  /** Empties one cache by its name (see GET /admin/cache), or "settings" to reload the config screen values. */
  @PostMapping("/refresh/{name}")
  public List<AppCaches.Stat> refresh(@PathVariable String name) {
    if ("settings".equals(name)) {
      settings.refresh();
    } else {
      AppCaches.Name n = AppCaches.Name.byKey(name);
      if (n == null) throw ApiException.notFound("Cache");
      caches.invalidate(n);
    }
    audit.log(CurrentUser.id(), "CACHE_REFRESH", "cache", name, null);
    log.atInfo().setMessage("Admin refreshed cache {}").addArgument(name).addKeyValue("event", "cache-refresh").addKeyValue("cache", name)
        .addKeyValue("actor", String.valueOf(CurrentUser.id())).log();
    return caches.stats();
  }
}
