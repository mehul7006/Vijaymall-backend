package com.vijay.mall.pos.api;

import com.vijay.mall.common.Limits;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.PosViews.PosAttributes;
import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.pos.app.StaffService;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.WeighedMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
  /**
   * Request body for saving the shop settings printed on bills. Blank optional fields fall back to defaults (state
   * code 24, invoice prefix VSM, weighed prefix 2, weighed mode {@code WEIGHT_GRAMS}); a missing {@code promise} or
   * {@code details} keeps the stored text.
   */
  public record ShopBody(@NotBlank @Size(max = Limits.SHOP_NAME) String shopName, @Size(max = Limits.SHOP_ADDRESS) String address,
                         @Pattern(regexp = Limits.PHONE_REGEX, message = "must be 7 to 15 digits; spaces, + and - are allowed") String phone,
                         String gstin, String stateCode,
                         String upiId, String invoicePrefix, @Size(max = Limits.RECEIPT_FOOTER) String receiptFooter, String weighedPrefix,
                         WeighedMode weighedMode, @Size(max = 200) String promise, @Size(max = 500) String details,
                         @Size(max = Limits.SHORT_NAME) String shortName, @Size(max = Limits.SHOP_NAME) String shopNameGu,
                         @Size(max = Limits.SHOP_ADDRESS) String addressGu,
                         @Size(max = 200) String promiseGu, @Size(max = 500) String detailsGu) {
    /** Convenience constructor without the short name and the Gujarati texts. */
    public ShopBody(String shopName, String address, String phone, String gstin, String stateCode, String upiId,
                    String invoicePrefix, String receiptFooter, String weighedPrefix, WeighedMode weighedMode,
                    String promise, String details) {
      this(shopName, address, phone, gstin, stateCode, upiId, invoicePrefix, receiptFooter, weighedPrefix, weighedMode,
          promise, details, null, null, null, null, null);
    }

    /** Convenience constructor without the "about the shop" text. */
    public ShopBody(String shopName, String address, String phone, String gstin, String stateCode, String upiId,
                    String invoicePrefix, String receiptFooter, String weighedPrefix, WeighedMode weighedMode,
                    String promise) {
      this(shopName, address, phone, gstin, stateCode, upiId, invoicePrefix, receiptFooter, weighedPrefix, weighedMode,
          promise, null);
    }

    /** keepPromise is used when the request leaves promise out, so saving the form does not blank it. */
    ShopSetting toSetting(String keepPromise) { return toSetting(keepPromise, null); }

    /** keepDetails works the same way for the "about the shop" text. */
    ShopSetting toSetting(String keepPromise, String keepDetails) {
      return toSetting(new ShopSetting("", "", "", "", "", "", "", "", "", WeighedMode.WEIGHT_GRAMS, keepPromise, keepDetails));
    }

    /** The stored settings give the texts to keep when the request leaves one out (promise, details, short name, Gujarati texts). */
    ShopSetting toSetting(ShopSetting keep) {
      return new ShopSetting(shopName.strip(), nz(address, ""), nz(phone, ""), nz(gstin, "").strip().toUpperCase(),
          nz(stateCode, "24"), nz(upiId, "").strip(), nz(invoicePrefix, "VSM").strip().toUpperCase(),
          nz(receiptFooter, "Thank you! Visit again."), nz(weighedPrefix, "2"),
          weighedMode == null ? WeighedMode.WEIGHT_GRAMS : weighedMode, promise == null ? keep.promise() : promise.strip(),
          details == null ? (keep.details() == null ? "" : keep.details()) : details.strip(),
          keepOrStrip(shortName, keep.shortName()), keepOrStrip(shopNameGu, keep.shopNameGu()), keepOrStrip(addressGu, keep.addressGu()),
          keepOrStrip(promiseGu, keep.promiseGu()), keepOrStrip(detailsGu, keep.detailsGu()));
    }
  }

  /**
   * Request body for a product's counter attributes: how it is sold (piece or weight), item code (PLU), GST percent,
   * HSN code and the barcodes that identify it.
   */
  public record PosBody(@NotNull SoldBy soldBy, String plu, int gstPercent, String hsn, List<String> barcodes) {}

  /**
   * Request body for creating a cashier account; the email is the sign-in identity. {@code name} is 1 to 80 characters,
   * {@code mobile} (optional) exactly 10 digits. {@code confirmPromote} must be true to turn an existing active customer into a cashier.
   */
  public record StaffBody(@NotBlank String email, @Size(min = 1, max = 80) String name,
                          @Pattern(regexp = "^$|\\d{10}", message = "must be 10 digits") String mobile, Boolean confirmPromote) {}

  /** Request body for blocking ({@code false}) or unblocking ({@code true}) a cashier. */
  @Schema(name = "PosActiveBody")
  public record ActiveBody(boolean active) {}

  /** active is false for a blocked cashier, so the roster can show who is switched off. */
  public record StaffView(UUID id, String email, String name, String mobile, boolean active) {
    static StaffView of(User u, boolean active) { return new StaffView(u.id(), u.email(), u.name(), u.mobile(), active); }
  }

  private static String keepOrStrip(String given, String stored) { return given == null ? stored : given.strip(); }

  private static String nz(String s, String d) { return s == null || s.isBlank() ? d : s; }

  private final ShopService shop;
  private final PosCatalogService catalog;
  private final StaffService staff;

  /**
   * Creates the controller.
   *
   * @param shop shop settings service
   * @param catalog counter catalog service
   * @param staff cashier account service
   */
  public AdminPosController(ShopService shop, PosCatalogService catalog, StaffService staff) {
    this.shop = shop;
    this.catalog = catalog;
    this.staff = staff;
  }

  /** Returns the full shop settings, including the GSTIN, UPI id and bill settings; admin only. */
  @GetMapping("/shop-settings")
  public ShopSetting getShop() { return shop.get(); }

  /**
   * Saves the shop settings; admin only. Answers 400 for an invalid GSTIN, state code, UPI id, invoice prefix or
   * weighed prefix.
   */
  @PutMapping("/shop-settings")
  public ShopSetting saveShop(@Valid @RequestBody ShopBody b) {
    ShopSetting current = shop.get();
    return shop.save(b.toSetting(current), CurrentUser.id());
  }

  /**
   * Returns the counter attributes and barcodes of one product; admin only. A product that was never configured shows
   * the defaults. Answers 404 for an unknown product.
   */
  @GetMapping("/products/{id}/pos")
  public PosAttributes attributes(@PathVariable UUID id) { return catalog.attributes(id); }

  /**
   * Saves a product's counter attributes and replaces its barcodes; admin only. Answers 400 for a bad PLU, GST rate
   * or barcode and 409 {@code PLU_TAKEN} or {@code BARCODE_TAKEN} when the code belongs to another product.
   */
  @PutMapping("/products/{id}/pos")
  public PosAttributes saveAttributes(@PathVariable UUID id, @Valid @RequestBody PosBody b) {
    return catalog.save(id, b.soldBy(), b.plu(), b.gstPercent(), b.hsn(), b.barcodes(), CurrentUser.id());
  }

  /** Lists the cashier accounts with whether each is active; admin only. */
  @GetMapping("/staff")
  public List<StaffView> staff() { return staff.list().stream().map(u -> StaffView.of(u, staff.isActive(u.id()))).toList(); }

  /**
   * Creates a cashier account (201); admin only. Answers 400 for an invalid email, name or mobile or an email that
   * belongs to an admin, and 409 {@code EMAIL_ALREADY_REGISTERED} when the email already has an account, unless the body
   * carries {@code confirmPromote: true} and the account is an active customer (which is then promoted).
   */
  @PostMapping("/staff")
  @ResponseStatus(HttpStatus.CREATED)
  public StaffView createStaff(@Valid @RequestBody StaffBody b) {
    return StaffView.of(staff.create(b.email(), b.name(), b.mobile(), Boolean.TRUE.equals(b.confirmPromote()), CurrentUser.id()), true);
  }

  /**
   * Blocks or unblocks a cashier; admin only. A blocked cashier cannot sign in. Answers 404 if the id is not a
   * cashier.
   */
  @PatchMapping("/staff/{id}")
  public void setActive(@PathVariable UUID id, @RequestBody ActiveBody b) { staff.setActive(id, b.active(), CurrentUser.id()); }
}
