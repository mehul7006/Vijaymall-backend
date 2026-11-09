package com.vijay.mall.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.order.api.CartController.ItemBody;
import com.vijay.mall.order.api.CartController.QuoteBody;
import com.vijay.mall.order.app.QuoteService;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.promotion.domain.PublicCoupon;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** The coupon picker tries a bounded number of coupons and one failing coupon never hides the others. */
class CartCouponControllerTest {
  private final QuoteService quotes = mock(QuoteService.class);
  private final PromotionService promotions = mock(PromotionService.class);
  private final CartCouponController controller = new CartCouponController(quotes, promotions);
  private final QuoteBody body = new QuoteBody(List.of(new ItemBody(UUID.randomUUID(), 1)), null, null, null, null);

  @BeforeEach
  void signIn() {
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(UUID.randomUUID().toString()).claim("role", "CUSTOMER")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null));
  }

  @AfterEach
  void clear() { SecurityContextHolder.clearContext(); }

  private static PublicCoupon coupon(String code, long minOrder, boolean categoryOnly) {
    UUID cat = categoryOnly ? UUID.randomUUID() : null;
    return new PublicCoupon(code, "FLAT", 1000, minOrder, 0, cat, null, null, true, false, 0);
  }

  private static Quote saving(long paise) {
    return new Quote(List.of(), 10_000, "X", paise, 0, 0, 0, 10_000 - paise, 0, List.of());
  }

  @Test
  void atMostTwentyCouponsAreTriedAndTheLikeliestComeFirst() {
    List<PublicCoupon> offered = new ArrayList<>();
    for (int i = 0; i < 25; i++) offered.add(coupon("C" + i, 100L * i, i % 2 == 1));
    when(promotions.listOffered()).thenReturn(offered);
    when(quotes.quote(any(), any())).thenReturn(saving(500));

    var out = controller.options(body);

    assertThat(out).hasSize(CartCouponController.MAX_TRIED);
    assertThat(out).extracting(CartCouponController.CouponOption::code).contains("C0", "C2").doesNotContain("C23", "C21");
  }

  @Test
  void anyBusinessErrorOnOneCouponListsItAsNotApplicableAndTheRestStillWork() {
    when(promotions.listOffered()).thenReturn(List.of(coupon("GOOD", 0, false), coupon("BAD", 0, false), coupon("MIN", 5_000, false)));
    when(quotes.quote(any(), any())).thenAnswer(inv -> {
      QuoteRequest r = inv.getArgument(1);
      return switch (r.couponCode()) {
        case "GOOD" -> saving(700);
        case "BAD" -> throw new ApiException(HttpStatus.BAD_REQUEST, "SOMETHING_ELSE", "That did not work");
        default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "COUPON_INVALID", "Add Rs 10 more",
            Map.of("reason", "MIN_ORDER", "shortfallPaise", 1000));
      };
    });

    var out = controller.options(body);

    assertThat(out).extracting(CartCouponController.CouponOption::code).containsExactly("GOOD", "BAD", "MIN");
    var bad = out.stream().filter(o -> o.code().equals("BAD")).findFirst().orElseThrow();
    assertThat(bad.applicable()).isFalse();
    assertThat(bad.message()).isEqualTo("That did not work");
    assertThat(bad.reason()).isNull();
    var min = out.stream().filter(o -> o.code().equals("MIN")).findFirst().orElseThrow();
    assertThat(min.reason()).isEqualTo("MIN_ORDER");
    assertThat(min.shortfallPaise()).isEqualTo(1000);
  }

  @Test
  void aNonBusinessFailurePropagates() {
    when(promotions.listOffered()).thenReturn(List.of(coupon("A", 0, false)));
    when(quotes.quote(any(), any())).thenThrow(new IllegalStateException("boom"));
    assertThatThrownBy(() -> controller.options(body)).isInstanceOf(IllegalStateException.class);
  }
}
