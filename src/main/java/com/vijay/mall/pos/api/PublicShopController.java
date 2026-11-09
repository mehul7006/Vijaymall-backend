package com.vijay.mall.pos.api;

import com.vijay.mall.pos.app.ShopProfileService;
import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * Everything the apps show about the shop: name, address, phone, tagline, about text (shop settings) plus logo, contact links, opening hours,
 * "open now" and whether online ordering is on (shop profile). Public: no GSTIN, UPI id or bill settings. The answer carries an ETag, so an
 * app that asks again gets a tiny "not modified" while nothing changed, and the admin's edits show on the next ask.
 */
@RestController
@RequestMapping("/api/v1/shop-settings")
public class PublicShopController {
  /** details is the longer "about the shop" text; every field is the admin's setting, nothing is kept in the app. */
  public record PublicShop(String shopName, String phone, String address, String promise, String details, String logoUrl, String email,
                           String whatsapp, String mapUrl, String website, String instagram, String facebook,
                           List<ShopProfileService.DayHours> hours, String holidayNote, boolean openNow, String todayHours,
                           boolean onlineOrdersOpen, String onlineOrdersMessage, String timezone) {
    public PublicShop(String shopName, String phone, String address, String promise, String details) {
      this(shopName, phone, address, promise, details, "", "", "", "", "", "", "", List.of(), "", false, "", true, "", "");
    }

    public PublicShop(String shopName, String phone, String address, String promise) {
      this(shopName, phone, address, promise, "");
    }
  }

  private final ShopService shop;
  private final ShopProfileService profile;

  public PublicShopController(ShopService shop) { this(shop, null); }

  @Autowired
  public PublicShopController(ShopService shop, ShopProfileService profile) {
    this.shop = shop;
    this.profile = profile;
  }

  public PublicShop get() {
    ShopSetting s = shop.get();
    String promise = s.promise() == null ? "" : s.promise();
    String details = s.details() == null ? "" : s.details();
    if (profile == null) return new PublicShop(s.shopName(), s.phone(), s.address(), promise, details);
    var v = profile.view();
    var p = v.profile();
    return new PublicShop(s.shopName(), s.phone(), s.address(), promise, details, p.logoUrl(), p.email(), p.whatsapp(), p.mapUrl(), p.website(),
        p.instagram(), p.facebook(), p.hours(), p.holidayNote(), v.openNow(), v.todayHours(), v.onlineOrdersOpen(),
        p.onlineOrdersEnabled() ? "" : p.onlineOrdersMessage(), v.timezone());
  }

  /** 304 (nothing sent) when the app already has this exact version, otherwise the shop details with their ETag. */
  @GetMapping
  public ResponseEntity<PublicShop> http(WebRequest request) {
    PublicShop body = get();
    String etag = "\"" + Integer.toHexString(body.hashCode()) + "\"";
    if (request.checkNotModified(etag)) return null;
    return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache()).body(body);
  }
}
