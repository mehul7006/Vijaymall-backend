package com.vijay.mall.catalog.api;

import com.vijay.mall.catalog.app.ReviewService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/product-groups/{id}/reviews")
public class ProductGroupReviewController {
  private final ReviewService reviews;

  public ProductGroupReviewController(ReviewService reviews) { this.reviews = reviews; }

  /** Public: the reviews of all variants of one product together, with one average and count. */
  @GetMapping
  public ReviewService.ReviewList list(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
    return reviews.listGroup(id, page, size);
  }
}
