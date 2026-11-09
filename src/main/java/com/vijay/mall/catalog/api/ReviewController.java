package com.vijay.mall.catalog.api;

import com.vijay.mall.catalog.app.ReviewService;
import com.vijay.mall.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Product review endpoints: read reviews (public), check whether I may review, and write or replace my review. */
@RestController
@RequestMapping("/api/v1/products/{productId}/reviews")
public class ReviewController {
  /** Request body for a review: a rating from 1 to 5 and an optional comment of at most 1000 characters. */
  @Schema(name = "ProductReviewBody")
  public record ReviewBody(@Min(1) @Max(5) int rating, @Size(max = 1000) String comment) {}

  private final ReviewService reviews;

  /** Creates the controller with the review service. */
  public ReviewController(ReviewService reviews) { this.reviews = reviews; }

  /** Public: anyone can read reviews. */
  @GetMapping
  public ReviewService.ReviewList list(@PathVariable UUID productId, @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
    return reviews.list(productId, page, size);
  }

  /** Signed-in customers: may I review this product yet, and what did I write? */
  @GetMapping("/mine")
  public ReviewService.Mine mine(@PathVariable UUID productId) { return reviews.mine(CurrentUser.id(), productId); }

  /** Signed-in customers who have had the product delivered. Writing again replaces the earlier review. */
  @PostMapping
  public ReviewService.Review write(@PathVariable UUID productId, @Valid @RequestBody ReviewBody body) {
    return reviews.write(CurrentUser.id(), productId, body.rating(), body.comment());
  }
}
