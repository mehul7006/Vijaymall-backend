package com.vijay.mall.pos.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.PosViews.PosAttributes;
import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.pos.app.StaffService;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.WeighedMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin side of the counter: shop details, per-product counter attributes and barcodes, cashier accounts. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminPosController {
  public record ShopBody(@NotBlank String shopName, String address, String phone, String gstin, String stateCode,
                         String upiId, String invoicePrefix, String receiptFooter, String weighedPrefix,
                         WeighedMode weighedMode) {
    ShopSetting toSetting() {
      return new ShopSetting(shopName.strip(), nz(address, ""), nz(phone, ""), nz(gstin, "").strip().toUpperCase(),
          nz(stateCode, "24"), nz(upiId, "").strip(), nz(invoicePrefix, "VSM").strip().toUpperCase(),
          nz(receiptFooter, "Thank you! Visit again."), nz(weighedPrefix, "2"),
          weighedMode == null ? WeighedMode.WEIGHT_GRAMS : weighedMode);
    }
  }

  public record PosBody(@NotNull SoldBy soldBy, String plu, int gstPercent, String hsn, List<String> barcodes) {}

  public record StaffBody(@NotBlank String email, String name, String mobile) {}

  public record ActiveBody(boolean active) {}

  public record StaffView(UUID id, String email, String name, String mobile) {
    static StaffView of(User u) { return new StaffView(u.id(), u.email(), u.name(), u.mobile()); }
  }

  private static String nz(String s, String d) { return s == null || s.isBlank() ? d : s; }

  private final ShopService shop;
  private final PosCatalogService catalog;
  private final StaffService staff;

  public AdminPosController(ShopService shop, PosCatalogService catalog, StaffService staff) {
    this.shop = shop;
    this.catalog = catalog;
    this.staff = staff;
  }

  @GetMapping("/shop-settings")
  public ShopSetting getShop() { return shop.get(); }

  @PutMapping("/shop-settings")
  public ShopSetting saveShop(@Valid @RequestBody ShopBody b) { return shop.save(b.toSetting(), CurrentUser.id()); }

  @GetMapping("/products/{id}/pos")
  public PosAttributes attributes(@PathVariable UUID id) { return catalog.attributes(id); }

  @PutMapping("/products/{id}/pos")
  public PosAttributes saveAttributes(@PathVariable UUID id, @Valid @RequestBody PosBody b) {
    return catalog.save(id, b.soldBy(), b.plu(), b.gstPercent(), b.hsn(), b.barcodes(), CurrentUser.id());
  }

  @GetMapping("/staff")
  public List<StaffView> staff() { return staff.list().stream().map(StaffView::of).toList(); }

  @PostMapping("/staff")
  @ResponseStatus(HttpStatus.CREATED)
  public StaffView createStaff(@Valid @RequestBody StaffBody b) {
    return StaffView.of(staff.create(b.email(), b.name(), b.mobile(), CurrentUser.id()));
  }

  @PatchMapping("/staff/{id}")
  public void setActive(@PathVariable UUID id, @RequestBody ActiveBody b) { staff.setActive(id, b.active(), CurrentUser.id()); }
}
