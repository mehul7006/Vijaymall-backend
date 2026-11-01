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
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewServiceTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");

  private final ReviewRepository reviews = mock(ReviewRepository.class);
  private final CatalogService catalog = mock(CatalogService.class);
  private final ProfileService profiles = mock(ProfileService.class);
  private final ReviewService service = new ReviewService(reviews, catalog, profiles, Clock.fixed(NOW, ZoneOffset.UTC));

  private final UUID product = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();

  @Test
  void writingAReviewSavesItAndRefreshesTheProductRating() {
    var row = new ReviewRepository.Row(UUID.randomUUID(), user, 4, "Fresh", NOW);
    when(reviews.upsert(product, user, 4, "Fresh", NOW)).thenReturn(row);
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
    verify(reviews, never()).upsert(any(), any(), anyInt(), any(), any());
  }

  @Test
  void aVeryLongCommentIsRejectedAndANullOneBecomesEmpty() {
    assertThatThrownBy(() -> service.write(user, product, 5, "x".repeat(1001))).isInstanceOf(ApiException.class);
    var row = new ReviewRepository.Row(UUID.randomUUID(), user, 5, "", NOW);
    when(reviews.upsert(product, user, 5, "", NOW)).thenReturn(row);
    when(reviews.summary(product)).thenReturn(new ReviewRepository.Summary(5, 1));
    when(profiles.get(user)).thenReturn(new User(user, null, null, "9876543210", "CUSTOMER"));
    assertThat(service.write(user, product, 5, null).reviewer()).isEqualTo("Customer");
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
