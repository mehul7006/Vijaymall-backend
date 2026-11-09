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
  /** Longest review comment accepted, in characters. */
  static final int MAX_COMMENT = 1000;

  /** reviewer is the first name only; verified is true when the review was written for a delivered order. */
  public record Review(UUID id, int rating, String comment, String reviewer, Instant createdAt, boolean verified) {
    /** A review not tied to a delivered order, so {@code verified} is false. */
    public Review(UUID id, int rating, String comment, String reviewer, Instant createdAt) {
      this(id, rating, comment, reviewer, createdAt, false);
    }
  }

  /** Whether the signed-in customer may review this product yet (it must have been delivered to them), and their review. */
  public record Mine(boolean canReview, Review review) {}

  /**
   * One page of reviews with the overall numbers.
   * @param rating average rating over all matching reviews (0 when there are none)
   * @param reviewCount total number of reviews, not only those on this page
   */
  public record ReviewList(List<Review> items, int page, int size, long total, double rating, int reviewCount) {}

  private final ReviewRepository reviews;
  private final CatalogService catalog;
  private final ProfileService profiles;
  private final PurchaseLookup purchases;
  private final Clock clock;

  /**
   * Creates the service. The purchase lookup is how the catalog asks the order module whether a product was delivered.
   */
  public ReviewService(ReviewRepository reviews, CatalogService catalog, ProfileService profiles, PurchaseLookup purchases,
                       Clock clock) {
    this.reviews = reviews;
    this.catalog = catalog;
    this.profiles = profiles;
    this.purchases = purchases;
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

  /** Reviews of all variants of one product together, newest first. The id may be the group id or any variant's id. */
  public ReviewList listGroup(UUID idOrGroupId, int page, int size) {
    List<UUID> ids = catalog.variantIds(idOrGroupId);
    Page<ReviewRepository.Row> p = reviews.pageOf(ids, Math.max(0, page), Page.clampSize(size));
    Map<UUID, User> people = profiles.users(p.items().stream().map(ReviewRepository.Row::userId).distinct().toList());
    List<Review> items = p.items().stream().map(r -> toReview(r, people.get(r.userId()))).toList();
    ReviewRepository.Summary s = reviews.summaryOf(ids);
    return new ReviewList(items, p.page(), p.size(), p.total(), Math.round(s.average() * 100.0) / 100.0, s.count());
  }

  /** Can this customer review the product, and what did they write? Used to show or hide the "write a review" button. */
  public Mine mine(UUID userId, UUID productId) {
    catalog.get(productId);
    boolean delivered = purchases.deliveredOrderWith(userId, productId).isPresent();
    Review mine = reviews.find(productId, userId).map(r -> toReview(r, profiles.get(userId))).orElse(null);
    return new Mine(delivered, mine);
  }

  /**
   * Adds the person's review, or replaces the one they wrote before. Only a customer who has had the product delivered
   * (or picked it up) may review it; the review remembers that order.
   */
  @Transactional
  public Review write(UUID userId, UUID productId, int rating, String comment) {
    if (rating < 1 || rating > 5) throw ApiException.validation("rating", "must be 1 to 5");
    String text = comment == null ? "" : comment.trim();
    if (text.length() > MAX_COMMENT) throw ApiException.validation("comment", "must be at most " + MAX_COMMENT + " characters");
    catalog.get(productId);
    UUID orderId = purchases.deliveredOrderWith(userId, productId).orElseThrow(() -> ApiException.unprocessable(
        "REVIEW_NOT_ALLOWED", "You can review a product after it has been delivered to you", Map.of()));
    ReviewRepository.Row row = reviews.upsert(productId, userId, rating, text, orderId, clock.instant());
    ReviewRepository.Summary s = reviews.summary(productId);
    catalog.updateRating(productId, Math.round(s.average() * 100.0) / 100.0, s.count());
    return toReview(row, profiles.get(userId));
  }

  private static Review toReview(ReviewRepository.Row r, User u) {
    return new Review(r.id(), r.rating(), r.comment(), firstName(u), r.createdAt(), r.verified());
  }

  /** The first word of the user's name for display, or {@code Customer} when the name is missing. */
  static String firstName(User u) {
    if (u == null || u.name() == null || u.name().isBlank()) return "Customer";
    return u.name().trim().split("\\s+")[0];
  }
}
