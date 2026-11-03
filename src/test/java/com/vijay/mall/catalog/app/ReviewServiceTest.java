package com.vijay.mall.catalog.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.infra.ReviewRepository;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.identity.domain.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewServiceTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");

  private final ReviewRepository reviews = mock(ReviewRepository.class);
  private final CatalogService catalog = mock(CatalogService.class);
  private final ProfileService profiles = mock(ProfileService.class);
  private final PurchaseLookup purchases = mock(PurchaseLookup.class);
  private final ReviewService service = new ReviewService(reviews, catalog, profiles, purchases, Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID order = UUID.randomUUID();

  private final UUID product = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();

  @Test
  void writingAReviewSavesItAndRefreshesTheProductRating() {
    var row = new ReviewRepository.Row(UUID.randomUUID(), user, 4, "Fresh", NOW);
    when(purchases.deliveredOrderWith(user, product)).thenReturn(Optional.of(order));
    when(reviews.upsert(product, user, 4, "Fresh", order, NOW)).thenReturn(row);
    when(reviews.summary(product)).thenReturn(new ReviewRepository.Summary(4.3333333, 3));
    when(profiles.get(user)).thenReturn(new User(user, "a@b.c", "Asha Patel", null, "CUSTOMER"));

    var out = service.write(user, product, 4, "  Fresh ");

    assertThat(out.reviewer()).isEqualTo("Asha");
    assertThat(out.rating()).isEqualTo(4);
    verify(catalog).updateRating(product, 4.33, 3);
  }

  @Test
  void ratingMustBeOneToFive() {
    assertThatThrownBy(() -> service.write(user, product, 0, "x")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.write(user, product, 6, "x")).isInstanceOf(ApiException.class);
    verify(reviews, never()).upsert(any(), any(), anyInt(), any(), any(), any());
  }

  @Test
  void aVeryLongCommentIsRejectedAndANullOneBecomesEmpty() {
    assertThatThrownBy(() -> service.write(user, product, 5, "x".repeat(1001))).isInstanceOf(ApiException.class);
    var row = new ReviewRepository.Row(UUID.randomUUID(), user, 5, "", NOW);
    when(purchases.deliveredOrderWith(user, product)).thenReturn(Optional.of(order));
    when(reviews.upsert(product, user, 5, "", order, NOW)).thenReturn(row);
    when(reviews.summary(product)).thenReturn(new ReviewRepository.Summary(5, 1));
    when(profiles.get(user)).thenReturn(new User(user, null, null, "9876543210", "CUSTOMER"));
    assertThat(service.write(user, product, 5, null).reviewer()).isEqualTo("Customer");
  }

  @Test
  void aProductNotDeliveredToThePersonCannotBeReviewed() {
    when(purchases.deliveredOrderWith(user, product)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.write(user, product, 5, "x"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("REVIEW_NOT_ALLOWED"));
    verify(reviews, never()).upsert(any(), any(), anyInt(), any(), any(), any());
    verify(catalog, never()).updateRating(any(), anyDouble(), anyInt());
  }

  @Test
  void mineSaysWhetherTheCustomerMayReviewAndShowsTheirReview() {
    var row = new ReviewRepository.Row(UUID.randomUUID(), user, 4, "Fresh", NOW, true);
    when(purchases.deliveredOrderWith(user, product)).thenReturn(Optional.of(order));
    when(reviews.find(product, user)).thenReturn(Optional.of(row));
    when(profiles.get(user)).thenReturn(new User(user, "a@b.c", "Asha Patel", null, "CUSTOMER"));
    var out = service.mine(user, product);
    assertThat(out.canReview()).isTrue();
    assertThat(out.review().verified()).isTrue();

    when(purchases.deliveredOrderWith(user, product)).thenReturn(Optional.empty());
    when(reviews.find(product, user)).thenReturn(Optional.empty());
    var none = service.mine(user, product);
    assertThat(none.canReview()).isFalse();
    assertThat(none.review()).isNull();
  }

  @Test
  void groupListingCoversEveryVariant() {
    UUID v2 = UUID.randomUUID();
    when(catalog.variantIds(product)).thenReturn(List.of(product, v2));
    when(reviews.pageOf(List.of(product, v2), 0, 20)).thenReturn(new Page<>(
        List.of(new ReviewRepository.Row(UUID.randomUUID(), user, 5, "Great", NOW, true)), 0, 20, 1));
    when(reviews.summaryOf(List.of(product, v2))).thenReturn(new ReviewRepository.Summary(4.567, 9));
    when(profiles.users(any())).thenReturn(Map.of(user, new User(user, "a@b.c", "Meera Shah", null, "CUSTOMER")));
    var out = service.listGroup(product, 0, 20);
    assertThat(out.items()).extracting(ReviewService.Review::reviewer).containsExactly("Meera");
    assertThat(out.rating()).isEqualTo(4.57);
    assertThat(out.reviewCount()).isEqualTo(9);
  }

  @Test
  void anUnknownOrHiddenProductCannotBeReviewed() {
    when(catalog.get(product)).thenThrow(ApiException.notFound("Product"));
    assertThatThrownBy(() -> service.write(user, product, 5, "x")).isInstanceOf(ApiException.class);
    verify(catalog, never()).updateRating(any(), anyDouble(), anyInt());
  }

  @Test
  void listShowsFirstNamesAndTheSummary() {
    UUID other = UUID.randomUUID();
    var rows = List.of(new ReviewRepository.Row(UUID.randomUUID(), user, 5, "Great", NOW),
        new ReviewRepository.Row(UUID.randomUUID(), other, 3, "", NOW));
    when(reviews.page(product, 0, 20)).thenReturn(new Page<>(rows, 0, 20, 2));
    when(reviews.summary(product)).thenReturn(new ReviewRepository.Summary(4, 2));
    when(profiles.users(any())).thenReturn(Map.of(user, new User(user, "a@b.c", "  Meera  Shah", null, "CUSTOMER")));

    var out = service.list(product, -3, 20);

    assertThat(out.items()).extracting(ReviewService.Review::reviewer).containsExactly("Meera", "Customer");
    assertThat(out.rating()).isEqualTo(4);
    assertThat(out.reviewCount()).isEqualTo(2);
    assertThat(out.total()).isEqualTo(2);
  }
}
