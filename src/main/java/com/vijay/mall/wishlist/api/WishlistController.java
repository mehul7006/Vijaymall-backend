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

@RestController
@RequestMapping("/api/v1/me/wishlist")
public class WishlistController {
  private final WishlistService wishlist;

  public WishlistController(WishlistService wishlist) { this.wishlist = wishlist; }

  @GetMapping
  public List<Product> list() { return wishlist.list(CurrentUser.id()); }

  @PostMapping("/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void add(@PathVariable UUID productId) { wishlist.add(CurrentUser.id(), productId); }

  @DeleteMapping("/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable UUID productId) { wishlist.remove(CurrentUser.id(), productId); }
}
