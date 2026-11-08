package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.ImageStore;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.pos.app.ShopProfileService.DayHours;
import com.vijay.mall.pos.app.ShopProfileService.Profile;
import com.vijay.mall.pos.infra.ShopProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class ShopProfileServiceTest {
  private static final String WEEK = "[{\"day\":\"MON\",\"open\":\"09:00\",\"close\":\"21:00\",\"closed\":false},"
      + "{\"day\":\"TUE\",\"open\":\"09:00\",\"close\":\"21:00\",\"closed\":false},{\"day\":\"WED\",\"open\":\"09:00\",\"close\":\"21:00\",\"closed\":false},"
      + "{\"day\":\"THU\",\"open\":\"09:00\",\"close\":\"21:00\",\"closed\":false},{\"day\":\"FRI\",\"open\":\"09:00\",\"close\":\"21:00\",\"closed\":false},"
      + "{\"day\":\"SAT\",\"open\":\"10:00\",\"close\":\"20:00\",\"closed\":false},{\"day\":\"SUN\",\"open\":\"00:00\",\"close\":\"00:00\",\"closed\":true}]";
  private final UUID admin = UUID.randomUUID();
  private ShopProfileRepository repo;
  private ImageStore images;
  private AuditService audit;
  private LiveEvents live;
  private Instant now;
  private ShopProfileService service;

  private ShopProfileRepository.Row row(String hours, boolean orders, String message) {
    return new ShopProfileRepository.Row("/img/shop/a_400.jpg", "hello@shop.in", "919876543210", "https://maps.example/x", "https://shop.in", "", "",
        hours, "Closed on Diwali", orders, message, Instant.parse("2026-11-01T00:00:00Z"));
  }

  private void clockAt(String istIso) {
    now = Instant.parse(istIso).minusSeconds(19800); // IST = UTC + 5:30
    service = new ShopProfileService(repo, images, audit, Clock.fixed(now, ZoneOffset.UTC), JsonMapper.builder().build());
    service.setLive(live);
  }

  @BeforeEach
  void setUp() {
    repo = mock(ShopProfileRepository.class);
    images = mock(ImageStore.class);
    audit = mock(AuditService.class);
    live = mock(LiveEvents.class);
    when(repo.get()).thenReturn(row(WEEK, true, ""));
    clockAt("2026-11-09T10:00:00Z"); // a Monday 10:00 in the shop
  }

  // ---------- open now ----------
  @Test
  void duringOpeningHoursTheShopIsOpenAndTodaysHoursAreShown() {
    var v = service.view();
    assertThat(v.openNow()).isTrue();
    assertThat(v.todayHours()).isEqualTo("09:00-21:00");
    assertThat(v.timezone()).isEqualTo("Asia/Kolkata");
    assertThat(v.onlineOrdersOpen()).isTrue();
    assertThat(v.profile().hours()).hasSize(7);
    assertThat(v.profile().email()).isEqualTo("hello@shop.in");
  }

  @Test
  void theEdgesOfTheOpeningHoursAreOpenInclusiveClosingExclusive() {
    clockAt("2026-11-09T08:59:00Z");
    assertThat(service.view().openNow()).as("a minute before opening").isFalse();
    clockAt("2026-11-09T09:00:00Z");
    assertThat(service.view().openNow()).as("exactly at opening").isTrue();
    clockAt("2026-11-09T20:59:00Z");
    assertThat(service.view().openNow()).isTrue();
    clockAt("2026-11-09T21:00:00Z");
    assertThat(service.view().openNow()).as("exactly at closing").isFalse();
  }

  @Test
  void aClosedDayIsClosedAllDayAndSaturdayHasItsOwnHours() {
    clockAt("2026-11-15T12:00:00Z"); // Sunday
    var sunday = service.view();
    assertThat(sunday.openNow()).isFalse();
    assertThat(sunday.todayHours()).isEqualTo("Closed");
    clockAt("2026-11-14T09:30:00Z"); // Saturday, opens at 10:00
    assertThat(service.view().openNow()).isFalse();
    assertThat(service.view().todayHours()).isEqualTo("10:00-20:00");
  }

  @Test
  void unreadableStoredHoursMeanClosedNotAnError() {
    when(repo.get()).thenReturn(row("not json", true, ""));
    var v = service.view();
    assertThat(v.profile().hours()).isEmpty();
    assertThat(v.openNow()).isFalse();
    assertThat(v.todayHours()).isEqualTo("Closed");
  }

  // ---------- pausing online orders ----------
  @Test
  void onlineOrdersCanBePausedWithTheShopsOwnMessage() {
    service.requireOpen();
    when(repo.get()).thenReturn(row(WEEK, false, "Closed for stock taking, back at 4 pm"));
    assertThatThrownBy(() -> service.requireOpen()).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo("ORDERS_PAUSED");
      assertThat(e.getMessage()).isEqualTo("Closed for stock taking, back at 4 pm");
    });
    when(repo.get()).thenReturn(row(WEEK, false, "  "));
    assertThatThrownBy(() -> service.requireOpen()).hasMessage(ShopProfileService.DEFAULT_PAUSED);
    assertThat(service.view().onlineOrdersOpen()).isFalse();
  }

  // ---------- saving ----------
  private Profile edit(String email, String whatsapp, String map, List<DayHours> hours) {
    return new Profile("", email, whatsapp, map, "https://shop.in", "https://instagram.com/shop", "https://facebook.com/shop", hours, "Closed on Diwali",
        true, "", null);
  }

  private static List<DayHours> week() {
    List<DayHours> h = new ArrayList<>();
    for (String d : List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")) h.add(new DayHours(d, "08:30", "22:00", false));
    return h;
  }

  @Test
  void aGoodEditIsStoredWithTheDaysInOrderAndTheLogoKept() {
    service.save(edit(" hello@shop.in ", "+91 98765 43210", " https://maps.example/x ", week()), admin);
    ArgumentCaptor<ShopProfileRepository.Row> saved = ArgumentCaptor.forClass(ShopProfileRepository.Row.class);
    verify(repo).save(saved.capture(), eq(now));
    var r = saved.getValue();
    assertThat(r.logoUrl()).as("the logo has its own call").isEqualTo("/img/shop/a_400.jpg");
    assertThat(r.email()).isEqualTo("hello@shop.in");
    assertThat(r.whatsapp()).isEqualTo("+919876543210");
    assertThat(r.mapUrl()).isEqualTo("https://maps.example/x");
    assertThat(r.hours()).startsWith("[{\"day\":\"MON\"").contains("\"day\":\"SUN\"");
    verify(audit).log(admin, "UPDATE", "shop_profile", 1, "profile");
    verify(live).area("SHOP");
  }

  @Test
  void aClosedDayIsStoredWithoutMeaninglessTimes() {
    var h = week();
    h.set(1, new DayHours("mon", "07:00", "07:00", true));
    service.save(edit("", "", "", h), admin);
    ArgumentCaptor<ShopProfileRepository.Row> saved = ArgumentCaptor.forClass(ShopProfileRepository.Row.class);
    verify(repo).save(saved.capture(), any());
    assertThat(saved.getValue().hours()).contains("{\"day\":\"MON\",\"open\":\"00:00\",\"close\":\"00:00\",\"closed\":true}");
    assertThat(saved.getValue().email()).isEmpty();
  }

  @Test
  void pausingIsAuditedAsSuch() {
    var p = new Profile("", "", "", "", "", "", "", week(), "", false, "Back soon", null);
    service.save(p, admin);
    verify(audit).log(admin, "UPDATE", "shop_profile", 1, "online ordering paused");
  }

  @Test
  void badInputIsRefusedAndNothingIsStored() {
    assertThatThrownBy(() -> service.save(edit("not-an-email", "", "", week()), admin)).hasMessageContaining("e-mail");
    assertThatThrownBy(() -> service.save(edit("", "12345", "", week()), admin)).hasMessageContaining("10 to 15 digits");
    assertThatThrownBy(() -> service.save(edit("", "", "http://maps.example/x", week()), admin)).hasMessageContaining("https://");
    assertThatThrownBy(() -> service.save(edit("", "", "https://" + "x".repeat(300), week()), admin)).hasMessageContaining("at most 300");
    assertThatThrownBy(() -> service.save(new Profile("", "", "", "", "", "", "", week(), "n".repeat(201), true, "", null), admin)).hasMessageContaining("at most 200");
    assertThatThrownBy(() -> service.save(edit("", "", "", null), admin)).hasMessageContaining("all seven days");
    assertThatThrownBy(() -> service.save(edit("", "", "", week().subList(0, 6)), admin)).hasMessageContaining("all seven days");
    var dup = week();
    dup.set(1, new DayHours("SUN", "08:30", "22:00", false));
    assertThatThrownBy(() -> service.save(edit("", "", "", dup), admin)).hasMessageContaining("exactly once");
    var wrongDay = week();
    wrongDay.set(0, new DayHours("FUNDAY", "08:30", "22:00", false));
    assertThatThrownBy(() -> service.save(edit("", "", "", wrongDay), admin)).hasMessageContaining("exactly once");
    var nullDay = week();
    nullDay.set(0, new DayHours(null, "08:30", "22:00", false));
    assertThatThrownBy(() -> service.save(edit("", "", "", nullDay), admin)).hasMessageContaining("exactly once");
    for (DayHours bad : List.of(new DayHours("SUN", "8:30", "22:00", false), new DayHours("SUN", "08:30", "25:00", false), new DayHours("SUN", null, "22:00", false),
        new DayHours("SUN", "08:30", null, false))) {
      var h = week();
      h.set(0, bad);
      assertThatThrownBy(() -> service.save(edit("", "", "", h), admin)).hasMessageContaining("times must look like");
    }
    var backwards = week();
    backwards.set(0, new DayHours("SUN", "22:00", "08:30", false));
    assertThatThrownBy(() -> service.save(edit("", "", "", backwards), admin)).hasMessageContaining("opening must be before closing");
    verify(repo, never()).save(any(), any());
  }

  // ---------- logo ----------
  @Test
  void aLogoIsStoredInTwoSizesAndCanBeRemoved() {
    when(images.save(any(), eq("shop"))).thenReturn("/img/shop/new_400.jpg");
    service.setLogo(new byte[] {1, 2, 3}, admin);
    ArgumentCaptor<ShopProfileRepository.Row> saved = ArgumentCaptor.forClass(ShopProfileRepository.Row.class);
    verify(repo).save(saved.capture(), eq(now));
    assertThat(saved.getValue().logoUrl()).isEqualTo("/img/shop/new_400.jpg");
    assertThat(saved.getValue().email()).as("the rest is untouched").isEqualTo("hello@shop.in");
    verify(audit).log(admin, "UPDATE", "shop_profile", 1, "logo");

    service.removeLogo(admin);
    verify(audit).log(admin, "UPDATE", "shop_profile", 1, "logo removed");
    verify(live, org.mockito.Mockito.times(2)).area("SHOP");
  }

  @Test
  void anEmptyLogoFileIsRefused() {
    assertThatThrownBy(() -> service.setLogo(new byte[0], admin)).hasMessageContaining("empty");
    assertThatThrownBy(() -> service.setLogo(null, admin)).hasMessageContaining("empty");
    verify(images, never()).save(any(), anyString());
  }

  @Test
  void theProfileIsCachedWhenACacheIsGiven() {
    var caches = new com.vijay.mall.common.cache.AppCaches(Clock.systemUTC());
    service.setCaches(caches);
    service.get();
    service.get();
    verify(repo, org.mockito.Mockito.times(1)).get();
  }

  @Test
  void pausedOrdersSayWhyWithTheShopsOwnMessageOrTheDefault() {
    when(repo.get()).thenReturn(row(WEEK, false, "Back on Monday"));
    assertThatThrownBy(() -> service.requireOpen()).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).isEqualTo("Back on Monday"));
    when(repo.get()).thenReturn(row(WEEK, false, "  "));
    assertThatThrownBy(() -> service.requireOpen()).isInstanceOf(ApiException.class);
    when(repo.get()).thenReturn(row(WEEK, false, null));
    assertThatThrownBy(() -> service.requireOpen()).isInstanceOf(ApiException.class);
    when(repo.get()).thenReturn(row(WEEK, true, null));
    service.requireOpen();
  }

  @Test
  void removingTheLogoWorksWithoutALiveFeed() {
    var quiet = new ShopProfileService(repo, images, audit, Clock.fixed(now, ZoneOffset.UTC), JsonMapper.builder().build());
    quiet.removeLogo(admin);
    verify(audit).log(admin, "UPDATE", "shop_profile", 1, "logo removed");
    verify(live, never()).area(any());
  }

  @Test
  void textFieldsLeftOutAreTreatedAsEmpty() {
    service.save(new Profile("", "", "", "", "", "", "", week(), null, true, null, null), admin);
    var saved = ArgumentCaptor.forClass(ShopProfileRepository.Row.class);
    verify(repo).save(saved.capture(), any());
    assertThat(saved.getValue().holidayNote()).isEmpty();
  }
}
