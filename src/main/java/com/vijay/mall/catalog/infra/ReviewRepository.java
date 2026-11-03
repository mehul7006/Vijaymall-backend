package com.vijay.mall.catalog.infra;

import com.vijay.mall.common.Page;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReviewRepository {
  /** verified is true when the review was written for a delivered order. */
  public record Row(UUID id, UUID userId, int rating, String comment, Instant createdAt, boolean verified) {
    public Row(UUID id, UUID userId, int rating, String comment, Instant createdAt) {
      this(id, userId, rating, comment, createdAt, false);
    }
  }

  /** Average rating (0 when there are no reviews) and how many reviews there are. */
  public record Summary(double average, int count) {}

  private final ReviewJpa jpa;

  ReviewRepository(ReviewJpa jpa) { this.jpa = jpa; }

  private static Row toRow(ReviewEntity e) { return new Row(e.id, e.userId, e.rating, e.comment, e.createdAt, e.orderId != null); }

  /** One review per person per product: a second one replaces the first. orderId is the delivered order it was written for. */
  @Transactional
  public Row upsert(UUID productId, UUID userId, int rating, String comment, UUID orderId, Instant now) {
    ReviewEntity e = jpa.findByProductIdAndUserId(productId, userId).orElseGet(() -> {
      ReviewEntity n = new ReviewEntity();
      n.id = UUID.randomUUID();
      n.productId = productId;
      n.userId = userId;
      n.createdAt = now;
      return n;
    });
    e.rating = rating;
    e.orderId = orderId;
    e.comment = comment;
    e.updatedAt = now;
    return toRow(jpa.saveAndFlush(e));
  }

  public Page<Row> page(UUID productId, int page, int size) {
    var result = jpa.findByProductId(productId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    List<Row> rows = result.getContent().stream().map(ReviewRepository::toRow).toList();
    return new Page<>(rows, page, size, result.getTotalElements());
  }

  public Optional<Row> find(UUID productId, UUID userId) {
    return jpa.findByProductIdAndUserId(productId, userId).map(ReviewRepository::toRow);
  }

  /** Reviews of several products at once (all variants of one product), newest first. */
  public Page<Row> pageOf(Collection<UUID> productIds, int page, int size) {
    if (productIds.isEmpty()) return new Page<>(List.of(), page, size, 0);
    var result = jpa.findByProductIdIn(productIds, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    return new Page<>(result.getContent().stream().map(ReviewRepository::toRow).toList(), page, size, result.getTotalElements());
  }

  public Summary summaryOf(Collection<UUID> productIds) {
    if (productIds.isEmpty()) return new Summary(0, 0);
    Double avg = jpa.averageOf(productIds);
    return new Summary(avg == null ? 0 : avg, (int) jpa.countByProductIdIn(productIds));
  }

  public Summary summary(UUID productId) {
    long count = jpa.countByProductId(productId);
    Double avg = jpa.average(productId);
    return new Summary(avg == null ? 0 : avg, (int) count);
  }
}
