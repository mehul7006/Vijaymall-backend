package com.vijay.mall.catalog.infra;

import com.vijay.mall.common.Page;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReviewRepository {
  public record Row(UUID id, UUID userId, int rating, String comment, Instant createdAt) {}

  /** Average rating (0 when there are no reviews) and how many reviews there are. */
  public record Summary(double average, int count) {}

  private final ReviewJpa jpa;

  ReviewRepository(ReviewJpa jpa) { this.jpa = jpa; }

  private static Row toRow(ReviewEntity e) { return new Row(e.id, e.userId, e.rating, e.comment, e.createdAt); }

  /** One review per person per product: a second one replaces the first. */
  @Transactional
  public Row upsert(UUID productId, UUID userId, int rating, String comment, Instant now) {
    ReviewEntity e = jpa.findByProductIdAndUserId(productId, userId).orElseGet(() -> {
      ReviewEntity n = new ReviewEntity();
      n.id = UUID.randomUUID();
      n.productId = productId;
      n.userId = userId;
      n.createdAt = now;
      return n;
    });
    e.rating = rating;
    e.comment = comment;
    e.updatedAt = now;
    return toRow(jpa.saveAndFlush(e));
  }

  public Page<Row> page(UUID productId, int page, int size) {
    var result = jpa.findByProductId(productId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    List<Row> rows = result.getContent().stream().map(ReviewRepository::toRow).toList();
    return new Page<>(rows, page, size, result.getTotalElements());
  }

  public Summary summary(UUID productId) {
    long count = jpa.countByProductId(productId);
    Double avg = jpa.average(productId);
    return new Summary(avg == null ? 0 : avg, (int) count);
  }
}
