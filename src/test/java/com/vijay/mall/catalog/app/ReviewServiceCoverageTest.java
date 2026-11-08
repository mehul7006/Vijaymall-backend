package com.vijay.mall.catalog.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.identity.domain.User;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewServiceCoverageTest {
  @Test
  void aReviewBuiltWithoutTheVerifiedFlagIsNotVerified() {
    UUID id = UUID.randomUUID();
    Instant at = Instant.parse("2027-03-01T10:00:00Z");
    var r = new ReviewService.Review(id, 5, "Good", "Asha", at);
    assertThat(r).isEqualTo(new ReviewService.Review(id, 5, "Good", "Asha", at, false));
    assertThat(r.verified()).isFalse();
  }

  @Test
  void firstNameFallsBackToCustomerForMissingOrBlankNames() {
    assertThat(ReviewService.firstName(null)).isEqualTo("Customer");
    assertThat(ReviewService.firstName(new User(UUID.randomUUID(), "a@b.c", null, null, "CUSTOMER"))).isEqualTo("Customer");
    assertThat(ReviewService.firstName(new User(UUID.randomUUID(), "a@b.c", "   ", null, "CUSTOMER"))).isEqualTo("Customer");
    assertThat(ReviewService.firstName(new User(UUID.randomUUID(), "a@b.c", "  Asha   Patel ", null, "CUSTOMER"))).isEqualTo("Asha");
  }
}
