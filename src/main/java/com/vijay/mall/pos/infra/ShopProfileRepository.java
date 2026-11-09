package com.vijay.mall.pos.infra;

import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes the single shop profile row. */
@Repository
public class ShopProfileRepository {
  /** hours is the stored JSON list of seven days; the service turns it into objects. */
  public record Row(String logoUrl, String email, String whatsapp, String mapUrl, String website, String instagram, String facebook,
                    String hours, String holidayNote, boolean onlineOrdersEnabled, String onlineOrdersMessage, Instant updatedAt,
                    String holidayNoteGu, String iconUrl, String icon192Url) {
    /** A row without the app icons (null). */
    public Row(String logoUrl, String email, String whatsapp, String mapUrl, String website, String instagram, String facebook,
               String hours, String holidayNote, boolean onlineOrdersEnabled, String onlineOrdersMessage, Instant updatedAt,
               String holidayNoteGu) {
      this(logoUrl, email, whatsapp, mapUrl, website, instagram, facebook, hours, holidayNote, onlineOrdersEnabled, onlineOrdersMessage,
          updatedAt, holidayNoteGu, null, null);
    }

    /** A row without the Gujarati holiday note. */
    public Row(String logoUrl, String email, String whatsapp, String mapUrl, String website, String instagram, String facebook,
               String hours, String holidayNote, boolean onlineOrdersEnabled, String onlineOrdersMessage, Instant updatedAt) {
      this(logoUrl, email, whatsapp, mapUrl, website, instagram, facebook, hours, holidayNote, onlineOrdersEnabled, onlineOrdersMessage,
          updatedAt, "");
    }
  }

  private final ShopProfileJpa jpa;

  ShopProfileRepository(ShopProfileJpa jpa) { this.jpa = jpa; }

  /** The shop profile. */
  public Row get() {
    ShopProfileEntity e = jpa.findById(1).orElseThrow();
    return new Row(e.logoUrl, e.email, e.whatsapp, e.mapUrl, e.website, e.instagram, e.facebook, e.hours, e.holidayNote,
        e.onlineOrdersEnabled, e.onlineOrdersMessage, e.updatedAt, e.holidayNoteGu == null ? "" : e.holidayNoteGu, e.iconUrl, e.icon192Url);
  }

  /** Overwrites the shop profile. */
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
    e.holidayNoteGu = r.holidayNoteGu() == null ? "" : r.holidayNoteGu();
    e.iconUrl = r.iconUrl();
    e.icon192Url = r.icon192Url();
    e.onlineOrdersEnabled = r.onlineOrdersEnabled();
    e.onlineOrdersMessage = r.onlineOrdersMessage();
    e.updatedAt = now;
  }
}
