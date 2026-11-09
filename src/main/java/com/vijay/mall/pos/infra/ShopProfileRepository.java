package com.vijay.mall.pos.infra;

import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ShopProfileRepository {
  /** hours is the stored JSON list of seven days; the service turns it into objects. */
  public record Row(String logoUrl, String email, String whatsapp, String mapUrl, String website, String instagram, String facebook,
                    String hours, String holidayNote, boolean onlineOrdersEnabled, String onlineOrdersMessage, Instant updatedAt) {}

  private final ShopProfileJpa jpa;

  ShopProfileRepository(ShopProfileJpa jpa) { this.jpa = jpa; }

  public Row get() {
    ShopProfileEntity e = jpa.findById(1).orElseThrow();
    return new Row(e.logoUrl, e.email, e.whatsapp, e.mapUrl, e.website, e.instagram, e.facebook, e.hours, e.holidayNote,
        e.onlineOrdersEnabled, e.onlineOrdersMessage, e.updatedAt);
  }

  @Transactional
  public void save(Row r, Instant now) {
    ShopProfileEntity e = jpa.findById(1).orElseThrow();
    e.logoUrl = r.logoUrl();
    e.email = r.email();
    e.whatsapp = r.whatsapp();
    e.mapUrl = r.mapUrl();
    e.website = r.website();
    e.instagram = r.instagram();
    e.facebook = r.facebook();
    e.hours = r.hours();
    e.holidayNote = r.holidayNote();
    e.onlineOrdersEnabled = r.onlineOrdersEnabled();
    e.onlineOrdersMessage = r.onlineOrdersMessage();
    e.updatedAt = now;
  }
}
