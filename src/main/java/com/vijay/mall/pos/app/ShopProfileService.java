package com.vijay.mall.pos.app;

import com.vijay.mall.catalog.app.ImageStore;
import com.vijay.mall.common.OnlineOrdering;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.pos.infra.ShopProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the apps show about the shop beyond its name, address and phone (those are in the shop settings): the logo, contact links,
 * opening hours with "open now", a holiday note, and the switch that pauses online ordering. Cached, and emptied when the admin saves.
 */
@Service
public class ShopProfileService implements OnlineOrdering {
  public record DayHours(String day, String open, String close, boolean closed) {}

  /** What is stored and edited. */
  public record Profile(String logoUrl, String email, String whatsapp, String mapUrl, String website, String instagram, String facebook,
                        List<DayHours> hours, String holidayNote, boolean onlineOrdersEnabled, String onlineOrdersMessage, Instant updatedAt) {}

  /** What the apps get, with the parts worked out from the clock. */
  public record PublicView(Profile profile, boolean openNow, String todayHours, boolean onlineOrdersOpen, String timezone) {}

  private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{10,15}$");
  private static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
  static final String DEFAULT_PAUSED = "We are not taking online orders right now. Please visit the shop or try again later.";

  private final ShopProfileRepository repo;
  private final ImageStore images;
  private final AuditService audit;
  private final Clock clock;
  private final JsonMapper json;

  private AppCaches caches;
  private LiveEvents live;

  public ShopProfileService(ShopProfileRepository repo, ImageStore images, AuditService audit, Clock clock, JsonMapper json) {
    this.repo = repo;
    this.images = images;
    this.audit = audit;
    this.clock = clock;
    this.json = json;
  }

  /** Optional (plain unit tests run without them). */
  @Autowired(required = false)
  void setCaches(AppCaches caches) { this.caches = caches; }

  @Autowired(required = false)
  void setLive(LiveEvents live) { this.live = live; }

  // ---------- reading ----------
  private Profile load() {
    ShopProfileRepository.Row r = repo.get();
    List<DayHours> hours;
    try {
      hours = json.readValue(r.hours(), new TypeReference<List<DayHours>>() {});
    } catch (RuntimeException e) {
      hours = List.of();
    }
    return new Profile(r.logoUrl(), r.email(), r.whatsapp(), r.mapUrl(), r.website(), r.instagram(), r.facebook(), hours, r.holidayNote(),
        r.onlineOrdersEnabled(), r.onlineOrdersMessage(), r.updatedAt());
  }

  public Profile get() { return caches == null ? load() : caches.get(AppCaches.Name.SHOP_PROFILE, "1", this::load); }

  /** The profile plus whether the shop is open this minute (shop time) and today's hours as text. */
  public PublicView view() {
    Profile p = get();
    ZonedDateTime now = clock.instant().atZone(InvoiceNumbers.SHOP_ZONE);
    DayHours today = p.hours().stream().filter(h -> h.day().equals(DAYS.get(now.getDayOfWeek().getValue() - 1))).findFirst().orElse(null);
    boolean open = today != null && !today.closed() && isBetween(now.toLocalTime(), today);
    String text = today == null || today.closed() ? "Closed" : today.open() + "-" + today.close();
    return new PublicView(p, open, text, p.onlineOrdersEnabled(), InvoiceNumbers.SHOP_ZONE.getId());
  }

  private static boolean isBetween(LocalTime t, DayHours h) {
    return !t.isBefore(LocalTime.parse(h.open())) && t.isBefore(LocalTime.parse(h.close()));
  }

  @Override
  public void requireOpen() {
    Profile p = get();
    if (!p.onlineOrdersEnabled()) {
      String msg = p.onlineOrdersMessage() == null || p.onlineOrdersMessage().isBlank() ? DEFAULT_PAUSED : p.onlineOrdersMessage();
      throw ApiException.unprocessable("ORDERS_PAUSED", msg, Map.of());
    }
  }

  // ---------- saving ----------
  /** The admin's edit of everything except the logo (which has its own call). */
  @Transactional
  public Profile save(Profile in, UUID actor) {
    String email = clean(in.email(), "email", 200);
    if (!email.isEmpty() && !EMAIL.matcher(email).matches()) throw ApiException.validation("email", "not a valid e-mail address");
    String whatsapp = clean(in.whatsapp(), "whatsapp", 20).replace(" ", "");
    if (!whatsapp.isEmpty() && !PHONE.matcher(whatsapp).matches()) throw ApiException.validation("whatsapp", "10 to 15 digits, optionally starting with +");
    String note = clean(in.holidayNote(), "holidayNote", 200);
    String message = clean(in.onlineOrdersMessage(), "onlineOrdersMessage", 200);
    ShopProfileRepository.Row old = repo.get();
    ShopProfileRepository.Row row = new ShopProfileRepository.Row(old.logoUrl(), email, whatsapp, link(in.mapUrl(), "mapUrl"), link(in.website(), "website"),
        link(in.instagram(), "instagram"), link(in.facebook(), "facebook"), hoursJson(in.hours()), note, in.onlineOrdersEnabled(), message, null);
    repo.save(row, clock.instant());
    audit.log(actor, "UPDATE", "shop_profile", 1, in.onlineOrdersEnabled() ? "profile" : "online ordering paused");
    changed();
    return load();
  }

  /** Stores the logo (two sizes) and returns the profile with its new address. */
  @Transactional
  public Profile setLogo(byte[] image, UUID actor) {
    if (image == null || image.length == 0) throw ApiException.validation("file", "file is empty");
    String url = images.save(image, "shop");
    updateLogo(url);
    audit.log(actor, "UPDATE", "shop_profile", 1, "logo");
    changed();
    return load();
  }

  @Transactional
  public Profile removeLogo(UUID actor) {
    updateLogo("");
    audit.log(actor, "UPDATE", "shop_profile", 1, "logo removed");
    changed();
    return load();
  }

  private void updateLogo(String url) {
    ShopProfileRepository.Row o = repo.get();
    repo.save(new ShopProfileRepository.Row(url, o.email(), o.whatsapp(), o.mapUrl(), o.website(), o.instagram(), o.facebook(), o.hours(),
        o.holidayNote(), o.onlineOrdersEnabled(), o.onlineOrdersMessage(), null), clock.instant());
  }

  private void changed() {
    if (live != null) live.area(CatalogChanged.SHOP);
  }

  private static String clean(String s, String field, int max) {
    String v = s == null ? "" : s.strip();
    if (v.length() > max) throw ApiException.validation(field, "at most " + max + " characters");
    return v;
  }

  private static String link(String s, String field) {
    String v = clean(s, field, 300);
    if (!v.isEmpty() && !v.toLowerCase().startsWith("https://")) throw ApiException.validation(field, "must start with https://");
    return v;
  }

  private String hoursJson(List<DayHours> hours) {
    if (hours == null || hours.size() != 7) throw ApiException.validation("hours", "give all seven days");
    Set<String> seen = new LinkedHashSet<>();
    List<DayHours> out = new ArrayList<>();
    for (DayHours h : hours) {
      String day = h.day() == null ? "" : h.day().strip().toUpperCase();
      if (!DAYS.contains(day) || !seen.add(day)) throw ApiException.validation("hours", "each day MON to SUN exactly once");
      if (h.closed()) {
        out.add(new DayHours(day, "00:00", "00:00", true));
        continue;
      }
      if (h.open() == null || h.close() == null || !TIME.matcher(h.open()).matches() || !TIME.matcher(h.close()).matches()) {
        throw ApiException.validation("hours", day + ": times must look like 09:00");
      }
      if (!LocalTime.parse(h.open()).isBefore(LocalTime.parse(h.close()))) throw ApiException.validation("hours", day + ": opening must be before closing");
      out.add(new DayHours(day, h.open(), h.close(), false));
    }
    out.sort(Comparator.comparingInt(h -> DAYS.indexOf(h.day())));
    return json.writeValueAsString(out);
  }
}
