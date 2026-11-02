package com.vijay.mall.pos.api;

import com.vijay.mall.pos.app.ShopService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The shop details customers see in the app (store card, footer, checkout header). No sign-in needed. */
@RestController
@RequestMapping("/api/v1/shop-settings")
public class PublicShopController {
  /** Only what is meant for customers: no GSTIN, UPI id, invoice prefix or barcode settings. */
  public record PublicShop(String shopName, String phone, String address) {}

  private final ShopService shop;

  public PublicShopController(ShopService shop) { this.shop = shop; }

  @GetMapping
  public PublicShop get() {
    var s = shop.get();
    return new PublicShop(s.shopName(), s.phone(), s.address());
  }
}
