package com.vijay.mall.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.promotion.api.PublicCouponController;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.promotion.domain.Coupon;
import com.vijay.mall.promotion.domain.PublicCoupon;
import com.vijay.mall.promotion.infra.CouponRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The public offers list: who is shown, what is shown, and the JSON shape. */
class PublicCouponTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
  private static final Instant EARLIER = NOW.minusSeconds(3600);
  private static final Instant LATER = NOW.plusSeconds(3600);

  private static Coupon coupon(String code, boolean active, Instant start, Instant end, int totalLimit, int used) {
    return new Coupon(UUID.randomUUID(), code, Coupon.FLAT, 500, 10_000, 0, null, 1, totalLimit, used, start, end, active);
  }

  @Test
  void activeCouponInsideItsDatesIsOffered() {
    assertThat(coupon("A", true, null, null, 0, 0).offeredAt(NOW)).isTrue();
    assertThat(coupon("A", true, EARLIER, LATER, 0, 0).offeredAt(NOW)).isTrue();
  }

  @Test
  void datesAreInclusiveAtBothEnds() {
    assertThat(coupon("A", true, NOW, null, 0, 0).offeredAt(NOW)).as("starts right now").isTrue();
    assertThat(coupon("A", true, null, NOW, 0, 0).offeredAt(NOW)).as("ends right now").isTrue();
  }

  @Test
  void inactiveNotStartedEndedOrUsedUpCouponsAreHidden() {
    assertThat(coupon("A", false, null, null, 0, 0).offeredAt(NOW)).as("inactive").isFalse();
    assertThat(coupon("A", true, LATER, null, 0, 0).offeredAt(NOW)).as("not started").isFalse();
    assertThat(coupon("A", true, null, EARLIER, 0, 0).offeredAt(NOW)).as("ended").isFalse();
    assertThat(coupon("A", true, null, null, 5, 5).offeredAt(NOW)).as("used up").isFalse();
    assertThat(coupon("A", true, null, null, 5, 4).offeredAt(NOW)).as("one use left").isTrue();
  }

  @Test
  void viewKeepsOfferDetailsAndDropsInternals() {
    var c = coupon("SAVE5", true, EARLIER, LATER, 100, 7);
    var v = PublicCoupon.from(c);
    assertThat(v).isEqualTo(new PublicCoupon("SAVE5", Coupon.FLAT, 500, 10_000, 0, null, EARLIER, LATER, true, false, 1));
    assertThat(PublicCoupon.class.getRecordComponents()).extracting(rc -> rc.getName())
        .doesNotContain("id", "totalLimit", "usedCount", "active");
  }

  @Test
  void viewCarriesTheCoinAndFirstOrderFlags() {
    var c = new Coupon(UUID.randomUUID(), "NEW100", Coupon.FLAT, 10_000, 0, 0, null, 1, 0, 0, null, null, true, false, true);
    var v = PublicCoupon.from(c);
    assertThat(v.worksWithCoins()).isFalse();
    assertThat(v.firstOrderOnly()).isTrue();
    assertThat(c.offeredAt(NOW)).as("the flags do not hide a coupon from the list").isTrue();
  }

  @Test
  void serviceListsOnlyOfferedCouponsInRepositoryOrder() {
    var repo = mock(CouponRepository.class);
    when(repo.allActive()).thenReturn(List.of(
        coupon("A", true, null, null, 0, 0),
        coupon("B", true, null, EARLIER, 0, 0),
        coupon("C", true, null, null, 1, 1),
        coupon("D", true, LATER, null, 0, 0),
        coupon("E", true, EARLIER, LATER, 3, 2)));
    var service = new PromotionService(repo, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(service.listOffered()).extracting(PublicCoupon::code).containsExactly("A", "E");
  }

  @Test
  void controllerReturnsTheListWithoutInternalFields() throws Exception {
    var service = mock(PromotionService.class);
    when(service.listOffered()).thenReturn(List.of(PublicCoupon.from(coupon("SAVE5", true, null, null, 10, 3))));
    var mvc = MockMvcBuilders.standaloneSetup(new PublicCouponController(service)).build();
    mvc.perform(get("/api/v1/coupons"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("SAVE5"))
        .andExpect(jsonPath("$[0].kind").value("FLAT"))
        .andExpect(jsonPath("$[0].value").value(500))
        .andExpect(jsonPath("$[0].minOrderPaise").value(10_000))
        .andExpect(jsonPath("$[0].id").doesNotExist())
        .andExpect(jsonPath("$[0].usedCount").doesNotExist())
        .andExpect(jsonPath("$[0].totalLimit").doesNotExist());
  }
}
