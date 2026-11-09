package com.vijay.mall.wishlist.api;

import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.wishlist.app.WishlistService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in customer's saved products under {@code /api/v1/me/wishlist}. Any signed-in user may call it. */
@RestController
@RequestMapping("/api/v1/me/wishlist")
public class WishlistController {
  private final WishlistService wishlist;

  /** Creates the controller (wired by Spring). */
  public WishlistController(WishlistService wishlist) { this.wishlist = wishlist; }

  /** Lists the saved products, newest first, leaving out products hidden since. */
  @GetMapping
  public List<Product> list() { return wishlist.list(CurrentUser.id()); }

  /**
   * Saves a product (204). Repeating it is harmless. Fails with 404 for an unknown or hidden product and 422 when the list is full (200 items).
   */
  @PostMapping("/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void add(@PathVariable UUID productId) { wishlist.add(CurrentUser.id(), productId); }

  /** Removes a saved product (204); removing one that is not saved is harmless. */
  @DeleteMapping("/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable UUID productId) { wishlist.remove(CurrentUser.id(), productId); }
}
