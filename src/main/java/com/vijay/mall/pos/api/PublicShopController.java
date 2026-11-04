package com.vijay.mall.pos.api;

import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The shop details customers see (store card, footer, checkout header). Public: no GSTIN, UPI id or bill settings. */
@RestController
@RequestMapping("/api/v1/shop-settings")
public class PublicShopController {
  /** details is the longer "about the shop" text; every field is the admin's shop setting, nothing is kept in the app. */
  public record PublicShop(String shopName, String phone, String address, String promise, String details) {
    public PublicShop(String shopName, String phone, String address, String promise) {
      this(shopName, phone, address, promise, "");
    }
  }

  private final ShopService shop;

  public PublicShopController(ShopService shop) { this.shop = shop; }

  @GetMapping
  public PublicShop get() {
    ShopSetting s = shop.get();
    return new PublicShop(s.shopName(), s.phone(), s.address(), s.promise() == null ? "" : s.promise(),
        s.details() == null ? "" : s.details());
  }
}
