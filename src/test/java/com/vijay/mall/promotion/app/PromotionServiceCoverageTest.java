package com.vijay.mall.promotion.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.promotion.domain.Coupon;
import com.vijay.mall.promotion.infra.CouponRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PromotionServiceCoverageTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");

  private final CouponRepository repo = mock(CouponRepository.class);
  private final AuditService audit = mock(AuditService.class);
  private final PromotionService service = new PromotionService(repo, audit, Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID actor = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();

  private static Coupon coupon(UUID id, String code, String kind, long value, int perUser, int total) {
    return new Coupon(id, code, kind, value, 0, 0, null, perUser, total, 0, null, null, true, true, false, List.of());
  }

  private static Coupon flat(long value) { return coupon(UUID.randomUUID(), "SAVE", Coupon.FLAT, value, 0, 0); }

  private static PricedLine line(long total) {
    return new PricedLine(UUID.randomUUID(), UUID.randomUUID(), "p", 1, total, total, true, true);
  }

  private static String reason(ApiException e) { return (String) e.details().get("reason"); }

  // ---- pricing ----

  @Test
  void normalizeTrimsAndUppercasesAndKeepsNull() {
    assertThat(PromotionService.normalize("  save10 ")).isEqualTo("SAVE10");
    assertThat(PromotionService.normalize(null)).isNull();
  }

  @Test
  void discountForReturnsTheDiscountInPaise() {
    when(repo.findByCode("SAVE")).thenReturn(Optional.of(flat(200)));
    assertThat(service.discountFor(" save", user, List.of(line(1000)), 1000)).isEqualTo(200);
  }

  @Test
  void unknownCodeIsNotFound() {
    when(repo.findByCode("NOPE")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.discountFor("nope", user, List.of(line(1000)), 1000))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo("COUPON_INVALID");
          assertThat(reason(e)).isEqualTo("NOT_FOUND");
        });
  }

  @Test
  void priorOrdersAreOnlyCountedForFirstOrderCoupons() {
    var plain = flat(100);
    when(repo.findByCode("SAVE")).thenReturn(Optional.of(plain));
    service.apply("SAVE", user, List.of(line(1000)), 1000, () -> { throw new AssertionError("must not be called"); });

    var first = new Coupon(UUID.randomUUID(), "FIRST", Coupon.FLAT, 100, 0, 0, null, 0, 0, 0, null, null, true, true, true, List.of());
    when(repo.findByCode("FIRST")).thenReturn(Optional.of(first));
    assertThatThrownBy(() -> service.apply("FIRST", user, List.of(line(1000)), 1000, () -> 2))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(reason(e)).isEqualTo("FIRST_ORDER_ONLY"));
    assertThat(service.apply("FIRST", user, List.of(line(1000)), 1000, () -> 0).discountPaise()).isEqualTo(100);
  }

  // ---- consume / release ----

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void consumeDoesNothingWithoutACode(String code) {
    service.consume(code, user, UUID.randomUUID(), 100);
    service.consume(null, user, UUID.randomUUID(), 100);
    verifyNoInteractions(repo);
  }

  @Test
  void consumeOfAnUnknownCodeIsRefused() {
    when(repo.findByCode("NOPE")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.consume("nope", user, UUID.randomUUID(), 100))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(reason(e)).isEqualTo("NOT_FOUND"));
  }

  @Test
  void consumeLocksChecksTheLimitTakesAUseAndRecordsTheRedemption() {
    var c = coupon(UUID.randomUUID(), "SAVE", Coupon.FLAT, 100, 2, 0);
    var order = UUID.randomUUID();
    when(repo.findByCode("SAVE")).thenReturn(Optional.of(c));
    when(repo.userUses(c.id(), user)).thenReturn(1);
    when(repo.takeUse(c.id())).thenReturn(1);

    service.consume(" save ", user, order, 100);

    var seq = inOrder(repo);
    seq.verify(repo).lockCustomerUse(c.id(), user);
    seq.verify(repo).userUses(c.id(), user);
    seq.verify(repo).takeUse(c.id());
    seq.verify(repo).insertRedemption(c.id(), user, order, 100);
  }

  @Test
  void consumeIsRefusedWhenTheCustomerHasUsedUpTheirLimit() {
    var c = coupon(UUID.randomUUID(), "SAVE", Coupon.FLAT, 100, 1, 0);
    when(repo.findByCode("SAVE")).thenReturn(Optional.of(c));
    when(repo.userUses(c.id(), user)).thenReturn(1);
    assertThatThrownBy(() -> service.consume("SAVE", user, UUID.randomUUID(), 100))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(reason(e)).isEqualTo("LIMIT_REACHED"));
    verify(repo, never()).takeUse(any());
    verify(repo, never()).insertRedemption(any(), any(), any(), anyLong());
  }

  @Test
  void consumeSkipsThePerCustomerCheckForAnonymousOrUnlimitedCoupons() {
    var limited = coupon(UUID.randomUUID(), "LIM", Coupon.FLAT, 100, 1, 0);
    when(repo.findByCode("LIM")).thenReturn(Optional.of(limited));
    when(repo.takeUse(limited.id())).thenReturn(1);
    service.consume("LIM", null, UUID.randomUUID(), 50); // no customer
    verify(repo, never()).lockCustomerUse(any(), any());

    var unlimited = coupon(UUID.randomUUID(), "FREE", Coupon.FLAT, 100, 0, 0);
    when(repo.findByCode("FREE")).thenReturn(Optional.of(unlimited));
    when(repo.takeUse(unlimited.id())).thenReturn(1);
    service.consume("FREE", user, UUID.randomUUID(), 50); // limit 0 = none
    verify(repo, never()).lockCustomerUse(any(), any());
    verify(repo, never()).userUses(unlimited.id(), user);
  }

  @Test
  void consumeIsRefusedWhenTheTotalLimitIsUsedUp() {
    var c = coupon(UUID.randomUUID(), "SAVE", Coupon.FLAT, 100, 0, 5);
    when(repo.findByCode("SAVE")).thenReturn(Optional.of(c));
    when(repo.takeUse(c.id())).thenReturn(0);
    assertThatThrownBy(() -> service.consume("SAVE", user, UUID.randomUUID(), 100))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(reason(e)).isEqualTo("LIMIT_REACHED"));
    verify(repo, never()).insertRedemption(any(), any(), any(), anyLong());
  }

  @Test
  void releaseGivesTheUseBackOnlyWhenAnOpenRedemptionExists() {
    var order = UUID.randomUUID();
    var cid = UUID.randomUUID();
    when(repo.openRedemptionCoupon(order)).thenReturn(Optional.of(cid));
    service.release(order);
    verify(repo).releaseRedemption(order, cid);

    var other = UUID.randomUUID();
    when(repo.openRedemptionCoupon(other)).thenReturn(Optional.empty());
    service.release(other);
    verify(repo, never()).releaseRedemption(eq(other), any());
  }

  // ---- offered ----

  @Test
  void offeredListOnlyHasCouponsThatCanBeUsedNow() {
    var live = coupon(UUID.randomUUID(), "LIVE", Coupon.FLAT, 100, 0, 0);
    var ended = new Coupon(UUID.randomUUID(), "ENDED", Coupon.FLAT, 100, 0, 0, null, 0, 0, 0, null, NOW.minusSeconds(1), true);
    when(repo.allActive()).thenReturn(List.of(live, ended));
    assertThat(service.listOffered()).extracting(p -> p.code()).containsExactly("LIVE");
  }

  // ---- admin ----

  @Test
  void createStoresTheNormalisedCouponAuditsAndTellsOpenScreens() {
    var live = mock(LiveEvents.class);
    service.setLive(live);
    var id = UUID.randomUUID();
    var stored = coupon(id, "NEW", Coupon.FLAT, 100, 0, 0);
    when(repo.findByCode("NEW")).thenReturn(Optional.empty());
    when(repo.insert(any())).thenReturn(id);
    when(repo.find(id)).thenReturn(Optional.of(stored));

    assertThat(service.create(coupon(null, " new ", Coupon.FLAT, 100, 0, 0), actor)).isEqualTo(stored);

    var cap = org.mockito.ArgumentCaptor.forClass(Coupon.class);
    verify(repo).insert(cap.capture());
    assertThat(cap.getValue().code()).isEqualTo("NEW");
    verify(audit).log(actor, "CREATE", "coupon", id, " new ");
    verify(live).area(CatalogChanged.COUPONS);
  }

  @Test
  void createWithoutALiveHubStillWorks() {
    var id = UUID.randomUUID();
    when(repo.findByCode("NEW")).thenReturn(Optional.empty());
    when(repo.insert(any())).thenReturn(id);
    when(repo.find(id)).thenReturn(Optional.of(coupon(id, "NEW", Coupon.FLAT, 100, 0, 0)));
    assertThat(service.create(coupon(null, "new", Coupon.FLAT, 100, 0, 0), actor).code()).isEqualTo("NEW");
  }

  @Test
  void createRefusesADuplicateCode() {
    when(repo.findByCode("DUP")).thenReturn(Optional.of(flat(1)));
    assertThatThrownBy(() -> service.create(coupon(null, "dup", Coupon.FLAT, 100, 0, 0), actor))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(400));
    verify(repo, never()).insert(any());
  }

  @Test
  void updateRefusesAnUnknownCouponAndACodeTakenByAnother() {
    var id = UUID.randomUUID();
    when(repo.find(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(id, coupon(id, "A", Coupon.FLAT, 100, 0, 0), actor))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));

    var existing = coupon(id, "A", Coupon.FLAT, 100, 0, 0);
    when(repo.find(id)).thenReturn(Optional.of(existing));
    when(repo.findByCode("TAKEN")).thenReturn(Optional.of(flat(1)));
    assertThatThrownBy(() -> service.update(id, coupon(id, "taken", Coupon.FLAT, 100, 0, 0), actor))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(400));
    verify(repo, never()).update(any(), any());
  }

  @Test
  void updateAllowsKeepingItsOwnCode() {
    var live = mock(LiveEvents.class);
    service.setLive(live);
    var id = UUID.randomUUID();
    var existing = coupon(id, "A", Coupon.FLAT, 100, 0, 0);
    when(repo.find(id)).thenReturn(Optional.of(existing));
    when(repo.findByCode("A")).thenReturn(Optional.of(existing));

    assertThat(service.update(id, coupon(id, "a", Coupon.PERCENT, 10, 0, 0), actor)).isEqualTo(existing);

    verify(repo).update(any(), any());
    verify(audit).log(actor, "UPDATE", "coupon", id, "a");
    verify(live).area(CatalogChanged.COUPONS);
  }

  @Test
  void deleteRemovesAnUnusedCouponAndDeactivatesAUsedOne() {
    var unused = UUID.randomUUID();
    var used = UUID.randomUUID();
    when(repo.find(unused)).thenReturn(Optional.of(flat(1)));
    when(repo.find(used)).thenReturn(Optional.of(flat(1)));
    when(repo.redemptionCount(unused)).thenReturn(0L);
    when(repo.redemptionCount(used)).thenReturn(2L);

    service.delete(unused, actor);
    verify(repo).delete(unused);
    verify(repo, never()).deactivate(unused);

    service.delete(used, actor);
    verify(repo).deactivate(used);
    verify(repo, never()).delete(used);
    verify(audit).log(actor, "DELETE", "coupon", used, null);
  }

  @Test
  void deleteOfAnUnknownCouponIsNotFound() {
    var id = UUID.randomUUID();
    when(repo.find(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(id, actor)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.status().value()).isEqualTo(404));
  }

  @Test
  void listReturnsEveryCoupon() {
    var all = List.of(flat(1), flat(2));
    when(repo.all()).thenReturn(all);
    assertThat(service.list()).isEqualTo(all);
  }

  // ---- validation of admin input ----

  private String field(Coupon in) {
    var e = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, () -> service.create(in, actor));
    return e.details().toString();
  }

  @Test
  void adminInputIsValidatedFieldByField() {
    assertThat(field(coupon(null, null, Coupon.FLAT, 100, 0, 0))).contains("code");
    assertThat(field(coupon(null, "  ", Coupon.FLAT, 100, 0, 0))).contains("code");
    assertThat(field(coupon(null, "A", "BOGUS", 100, 0, 0))).contains("kind");
    assertThat(field(coupon(null, "A", Coupon.PERCENT, 0, 0, 0))).contains("value");
    assertThat(field(coupon(null, "A", Coupon.PERCENT, 101, 0, 0))).contains("value");
    assertThat(field(coupon(null, "A", Coupon.FLAT, 100, -1, 0))).contains("perUserLimit");
    assertThat(field(coupon(null, "A", Coupon.FLAT, 100, 0, -1))).contains("totalLimit");
    var backwards = new Coupon(null, "A", Coupon.FLAT, 100, 0, 0, null, 0, 0, 0, NOW, NOW.minusSeconds(5), true);
    assertThat(field(backwards)).contains("endAt");
    verify(repo, never()).insert(any());
  }

  @Test
  void aPercentCouponOfExactlyOneHundredIsAllowed() {
    var id = UUID.randomUUID();
    when(repo.findByCode("FULL")).thenReturn(Optional.empty());
    when(repo.insert(any())).thenReturn(id);
    when(repo.find(id)).thenReturn(Optional.of(coupon(id, "FULL", Coupon.PERCENT, 100, 0, 0)));
    assertThat(service.create(coupon(null, "full", Coupon.PERCENT, 100, 0, 0), actor).value()).isEqualTo(100);
  }
}
