package com.vijay.mall.catalog.app;

import com.vijay.mall.catalog.infra.ReviewRepository;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.identity.domain.User;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer reviews of a product. The average and count are copied onto the product so cards can show them cheaply. */
@Service
public class ReviewService {
  static final int MAX_COMMENT = 1000;

  /** reviewer is the first name only. */
  public record Review(UUID id, int rating, String comment, String reviewer, Instant createdAt) {}

  public record ReviewList(List<Review> items, int page, int size, long total, double rating, int reviewCount) {}

  private final ReviewRepository reviews;
  private final CatalogService catalog;
  private final ProfileService profiles;
  private final Clock clock;

  public ReviewService(ReviewRepository reviews, CatalogService catalog, ProfileService profiles, Clock clock) {
    this.reviews = reviews;
    this.catalog = catalog;
    this.profiles = profiles;
    this.clock = clock;
  }

  /** Newest first. The product must be one customers can see. */
  public ReviewList list(UUID productId, int page, int size) {
    catalog.get(productId);
    Page<ReviewRepository.Row> p = reviews.page(productId, Math.max(0, page), Page.clampSize(size));
    Map<UUID, User> people = profiles.users(p.items().stream().map(ReviewRepository.Row::userId).distinct().toList());
    List<Review> items = p.items().stream().map(r -> toReview(r, people.get(r.userId()))).toList();
    ReviewRepository.Summary s = reviews.summary(productId);
    return new ReviewList(items, p.page(), p.size(), p.total(), s.average(), s.count());
  }

  /** Adds the person's review, or replaces the one they wrote before. */
  @Transactional
  public Review write(UUID userId, UUID productId, int rating, String comment) {
    if (rating < 1 || rating > 5) throw ApiException.validation("rating", "must be 1 to 5");
    String text = comment == null ? "" : comment.trim();
    if (text.length() > MAX_COMMENT) throw ApiException.validation("comment", "must be at most " + MAX_COMMENT + " characters");
    catalog.get(productId);
    ReviewRepository.Row row = reviews.upsert(productId, userId, rating, text, clock.instant());
    ReviewRepository.Summary s = reviews.summary(productId);
    catalog.updateRating(productId, Math.round(s.average() * 100.0) / 100.0, s.count());
    return toReview(row, profiles.get(userId));
  }

  private static Review toReview(ReviewRepository.Row r, User u) {
    return new Review(r.id(), r.rating(), r.comment(), firstName(u), r.createdAt());
  }

  static String firstName(User u) {
    if (u == null || u.name() == null || u.name().isBlank()) return "Customer";
    return u.name().trim().split("\\s+")[0];
  }
}
