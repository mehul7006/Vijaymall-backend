package com.vijay.mall.catalog.infra;

import com.vijay.mall.catalog.domain.HotBanner;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BannerRepository {
  private final BannerJpa jpa;

  BannerRepository(BannerJpa jpa) { this.jpa = jpa; }

  private static HotBanner toDomain(HotBannerEntity e) {
    return new HotBanner(e.id, e.title, e.subtitle, e.imageUrl, e.startAt, e.endAt, e.active);
  }

  public List<HotBanner> all() { return jpa.allOrdered().stream().map(BannerRepository::toDomain).toList(); }

  public List<HotBanner> live(Instant now) { return jpa.live(now).stream().map(BannerRepository::toDomain).toList(); }

  public Optional<HotBanner> find(UUID id) { return jpa.findById(id).map(BannerRepository::toDomain); }

  @Transactional
  public UUID insert(String title, String subtitle, String imageUrl, Instant start, Instant end, boolean active) {
    HotBannerEntity e = new HotBannerEntity();
    e.id = UUID.randomUUID();
    fill(e, title, subtitle, imageUrl, start, end, active);
    return jpa.save(e).id;
  }

  @Transactional
  public void update(UUID id, String title, String subtitle, String imageUrl, Instant start, Instant end, boolean active) {
    jpa.findById(id).ifPresent(e -> fill(e, title, subtitle, imageUrl, start, end, active));
  }

  @Transactional
  public void delete(UUID id) { jpa.deleteById(id); }

  private static void fill(HotBannerEntity e, String title, String subtitle, String imageUrl, Instant start, Instant end,
                           boolean active) {
    e.title = title;
    e.subtitle = subtitle;
    e.imageUrl = imageUrl;
    e.startAt = start;
    e.endAt = end;
    e.active = active;
  }
}
