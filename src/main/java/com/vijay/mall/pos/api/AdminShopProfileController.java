package com.vijay.mall.pos.api;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.pos.app.ShopProfileService;
import com.vijay.mall.pos.app.ShopProfileService.DayHours;
import com.vijay.mall.pos.app.ShopProfileService.Profile;
import java.io.IOException;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * What the apps show about the shop: logo, contact links, opening hours, holiday note, and the pause switch for online orders. The
 * shop name, address, phone and billing details are edited in Shop settings (/admin/shop-settings). Admin only (everything under /admin).
 */
@RestController
@RequestMapping("/api/v1/admin/shop-profile")
public class AdminShopProfileController {
  public record ProfileBody(String email, String whatsapp, String mapUrl, String website, String instagram, String facebook, List<DayHours> hours,
                            String holidayNote, Boolean onlineOrdersEnabled, String onlineOrdersMessage) {}

  private final ShopProfileService profile;

  public AdminShopProfileController(ShopProfileService profile) { this.profile = profile; }

  @GetMapping
  public Profile get() { return profile.get(); }

  /** Saves the contact links, hours and pause switch. The logo has its own call. Leave onlineOrdersEnabled out to keep ordering on. */
  @PutMapping
  public Profile save(@RequestBody ProfileBody b) {
    return profile.save(new Profile("", b.email(), b.whatsapp(), b.mapUrl(), b.website(), b.instagram(), b.facebook(), b.hours(), b.holidayNote(),
        b.onlineOrdersEnabled() == null || b.onlineOrdersEnabled(), b.onlineOrdersMessage(), null), CurrentUser.id());
  }

  /** The shop logo: a picture file. It is stored in two sizes (the address shown is the small one; swap _400 for _1000 for the large). */
  @PostMapping("/logo")
  public Profile logo(@RequestParam("file") MultipartFile file) throws IOException {
    if (file.isEmpty()) throw ApiException.validation("file", "file is empty");
    return profile.setLogo(file.getBytes(), CurrentUser.id());
  }

  @DeleteMapping("/logo")
  public Profile removeLogo() { return profile.removeLogo(CurrentUser.id()); }
}
