package com.vijay.mall.flash.app;

import com.vijay.mall.common.FlashPricing;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.common.metrics.Metrics;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.flash.domain.FlashKind;
import com.vijay.mall.flash.domain.FlashMath;
import com.vijay.mall.flash.infra.FlashRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The flash sale side of pricing: which sale price applies to a product right now, and the exact counting of the units sold.
 * Sale definitions (not quantities) are cached for a few seconds; the units left are always read from the table, and the cap
 * itself is enforced by one conditional UPDATE, so no cache or race can sell more than the cap.
 */
@Service
public class FlashPricingService implements FlashPricing {
  private static final Logger log = LoggerFactory.getLogger(FlashPricingService.class);

  /** One product's place in a sale that has not ended. */
  record ItemDef(UUID saleId, String saleName, String bannerText, String description, UUID itemId, UUID productId, FlashMath.Rule rule,
                 Integer maxQty, int customerLimit, Instant startsAt, Instant endsAt, String channel) {
    boolean isOn(Instant now) { return !now.isBefore(startsAt) && now.isBefore(endsAt); }

    boolean open(Channel c) { return "BOTH".equals(channel) || channel.equals(c.name()); }
  }

  /** The definitions of every sale that has not ended, by product, in start order. */
  record Defs(Map<UUID, List<ItemDef>> byProduct, List<FlashRepository.SaleWithItems> sales) {}

  private final FlashRepository repo;
  private final Clock clock;
  private AppCaches caches;
  private MeterRegistry metrics;
  private OutboxService outbox;

  public FlashPricingService(FlashRepository repo, Clock clock) {
    this.repo = repo;
    this.clock = clock;
  }

  @Autowired(required = false)
  public void setCaches(AppCaches caches) { this.caches = caches; }

  /** Optional (plain unit tests run without it): counters and the price lookup timer. */
  @Autowired(required = false)
  public void setMetrics(MeterRegistry metrics) { this.metrics = metrics; }

  /** Optional (plain unit tests run without it): tells staff when an item sells out. */
  @Autowired(required = false)
  public void setOutbox(OutboxService outbox) { this.outbox = outbox; }

  Defs defs() {
    return caches == null ? loadDefs() : caches.get(AppCaches.Name.FLASH, "defs", this::loadDefs);
  }

  private Defs loadDefs() {
    List<FlashRepository.SaleWithItems> sales = repo.openSales(clock.instant());
    Map<UUID, List<ItemDef>> byProduct = new LinkedHashMap<>();
    for (FlashRepository.SaleWithItems sw : sales) {
      FlashRepository.Sale s = sw.sale();
      FlashKind saleKind = FlashKind.parse(s.discountKind());
      for (FlashRepository.Item i : sw.items()) {
        FlashMath.Rule rule = FlashMath.effective(i.discountKind() == null ? null : FlashKind.parse(i.discountKind()), i.discountValue(),
            saleKind, s.discountValue());
        int limit = i.perCustomerLimit() != null ? i.perCustomerLimit() : s.perCustomerLimit();
        byProduct.computeIfAbsent(i.productId(), _ -> new ArrayList<>()).add(new ItemDef(s.id(), s.name(), s.bannerText(), s.description(),
            i.id(), i.productId(), rule, i.maxQty(), limit, s.startsAt(), s.endsAt(), s.channel()));
      }
    }
    return new Defs(byProduct, sales);
  }

  private Optional<ItemDef> activeDef(UUID productId, Channel channel, Instant now) {
    return defs().byProduct().getOrDefault(productId, List.of()).stream().filter(d -> d.isOn(now) && d.open(channel)).findFirst();
  }

  // ---------- price ----------
  @Override
  public Optional<Applied> apply(UUID productId, Channel channel, long listPricePaise, UUID userIdOrNull, int qty) {
    return Metrics.timed(metrics, "mall.flash.price.lookup", "Time to look up the flash sale price of a product",
        () -> lookup(productId, channel, listPricePaise, userIdOrNull, qty));
  }

  private Optional<Applied> lookup(UUID productId, Channel channel, long list, UUID userId, int qty) {
    if (qty <= 0) return Optional.empty();
    Optional<ItemDef> def = activeDef(productId, channel, clock.instant());
    if (def.isEmpty()) return Optional.empty();
    ItemDef d = def.get();
    Optional<Long> price = FlashMath.salePrice(d.rule(), list);
    if (price.isEmpty()) return Optional.empty();
    Integer left = d.maxQty() == null ? null : Math.max(0, d.maxQty() - repo.soldQtys(List.of(d.itemId())).getOrDefault(d.itemId(), 0));
    Integer mine = userId == null || d.customerLimit() <= 0 ? null : Math.max(0, d.customerLimit() - repo.userQty(d.itemId(), userId));
    int n = FlashMath.saleQty(qty, left, mine);
    return n <= 0 ? Optional.empty() : Optional.of(new Applied(d.saleId(), d.itemId(), price.get(), n, list));
  }

  @Override
  public Map<UUID, Offer> offers(Map<UUID, Long> listPrices) {
    Instant now = clock.instant();
    Map<UUID, ItemDef> on = new LinkedHashMap<>();
    Map<UUID, Long> price = new LinkedHashMap<>();
    listPrices.forEach((product, list) -> activeDef(product, Channel.ONLINE, now).ifPresent(d ->
        FlashMath.salePrice(d.rule(), list).ifPresent(p -> {
          on.put(product, d);
          price.put(product, p);
        })));
    if (on.isEmpty()) return Map.of();
    Map<UUID, Integer> sold = repo.soldQtys(on.values().stream().map(ItemDef::itemId).toList());
    Map<UUID, Offer> out = new LinkedHashMap<>();
    on.forEach((product, d) -> {
      Integer left = d.maxQty() == null ? null : Math.max(0, d.maxQty() - sold.getOrDefault(d.itemId(), 0));
      if (left != null && left == 0) return; // sold out: the product sells at the normal price again
      long list = listPrices.get(product);
      out.put(product, new Offer(d.saleId(), d.saleName(), list, price.get(product), FlashMath.discountPercent(list, price.get(product)),
          left, d.customerLimit(), d.startsAt(), d.endsAt()));
    });
    return out;
  }

  // ---------- counting ----------
  @Override
  @Transactional
  public void consume(Channel channel, UUID refId, UUID userId, List<Claim> claims) { claimAll(channel, refId, userId, claims, true); }

  @Override
  @Transactional
  public void consumeLenient(Channel channel, UUID refId, UUID userId, List<Claim> claims) { claimAll(channel, refId, userId, claims, false); }

  private void claimAll(Channel channel, UUID refId, UUID userId, List<Claim> claims, boolean strict) {
    // same order every time, so two orders cannot wait for each other's item rows
    List<Claim> ordered = claims.stream().sorted(Comparator.comparing(Claim::flashSaleId).thenComparing(Claim::productId)).toList();
    for (Claim c : ordered) {
      Optional<String> refused = claimOne(channel, refId, userId, c);
      if (refused.isPresent()) {
        String reason = refused.get();
        log.atInfo().setMessage("Flash sale refused").addKeyValue("event", "flash-refused").addKeyValue("reason", reason)
            .addKeyValue("saleId", c.flashSaleId()).addKeyValue("productId", c.productId()).addKeyValue("qty", c.qty())
            .addKeyValue("channel", channel).addKeyValue("userId", userId).addKeyValue("strict", strict).log();
        Metrics.count(metrics, "mall.flash.refused", "Flash sale purchases refused at checkout", "reason", reason, 1);
        if (strict) throw new Refused(reason);
      }
    }
  }

  private Optional<String> claimOne(Channel channel, UUID refId, UUID userId, Claim c) {
    Instant now = clock.instant();
    List<String> channels = List.of(channel.name(), "BOTH");
    Optional<FlashRepository.Claimed> claimed = repo.claim(c.flashSaleId(), c.productId(), c.qty(), channels, now);
    if (claimed.isEmpty()) return Optional.of(repo.refusalReason(c.flashSaleId(), channels, now));
    FlashRepository.Claimed k = claimed.get();
    int limit = k.itemLimit() != null ? k.itemLimit() : k.saleLimit();
    // the item row is locked until commit, so two orders of one customer are counted one after the other
    if (limit > 0 && userId != null && repo.userQty(k.itemId(), userId) + c.qty() > limit) {
      repo.unclaim(k.itemId(), c.qty());
      return Optional.of("CUSTOMER_LIMIT");
    }
    boolean order = channel == Channel.ONLINE;
    repo.insertConsumption(k.itemId(), c.flashSaleId(), c.productId(), channel.name(), order ? refId : null, order ? null : refId, userId,
        c.qty(), c.unitPricePaise(), c.listPricePaise(), now);
    if (k.maxQty() != null && k.soldQty() >= k.maxQty() && repo.markSoldOutNotified(k.itemId(), now) && outbox != null) {
      outbox.add("FLASH_ITEM_SOLD_OUT", Map.of("saleId", c.flashSaleId(), "saleName", k.saleName(), "productId", c.productId()));
      log.atInfo().setMessage("Flash sale item sold out").addKeyValue("event", "flash-item-sold-out")
          .addKeyValue("saleId", c.flashSaleId()).addKeyValue("productId", c.productId()).log();
    }
    log.atInfo().setMessage("Flash sale units used").addKeyValue("event", "flash-consumed").addKeyValue("saleId", c.flashSaleId())
        .addKeyValue("productId", c.productId()).addKeyValue("qty", c.qty()).addKeyValue("channel", channel)
        .addKeyValue("unitPricePaise", c.unitPricePaise()).addKeyValue("listPricePaise", c.listPricePaise())
        .addKeyValue("ref", refId).addKeyValue("userId", userId).log();
    long revenue = c.unitPricePaise() * c.qty();
    long discount = (c.listPricePaise() - c.unitPricePaise()) * c.qty();
    afterCommit(() -> {
      Metrics.count(metrics, "mall.flash.units.sold", "Units sold at a flash sale price", "channel", channel.name(), c.qty());
      Metrics.count(metrics, "mall.flash.revenue.paise", "Revenue from flash sale units, in paise", "channel", channel.name(), revenue);
      Metrics.count(metrics, "mall.flash.discount.paise", "Discount given by flash sales, in paise", "channel", channel.name(), discount);
    });
    return Optional.empty();
  }

  @Override
  @Transactional
  public void release(UUID refId) {
    for (FlashRepository.Released r : repo.release(refId, clock.instant())) {
      log.atInfo().setMessage("Flash sale units given back").addKeyValue("event", "flash-released").addKeyValue("saleId", r.saleId())
          .addKeyValue("productId", r.productId()).addKeyValue("qty", r.qty()).addKeyValue("channel", r.channel())
          .addKeyValue("ref", refId).log();
    }
  }

  /** Runs after the surrounding transaction commits (a rolled-back order must not count); at once when there is none. */
  static void afterCommit(Runnable r) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { r.run(); }
      });
    } else {
      r.run();
    }
  }
}
