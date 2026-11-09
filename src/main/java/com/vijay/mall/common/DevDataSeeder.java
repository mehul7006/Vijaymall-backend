package com.vijay.mall.common;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.catalog.domain.Category;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.StaffService;
import com.vijay.mall.pos.domain.BarcodeParser;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.promotion.domain.Coupon;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinMode;
import com.vijay.mall.wallet.domain.CoinRule;
import com.vijay.mall.wallet.domain.StoredRule;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fills an empty dev database with sample shop data. Runs only with the "dev" profile and only once. */
@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {
  private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

  
  private final CatalogService catalog;
  private final WalletService wallet;
  private final PromotionService promotions;
  private final PosCatalogService posCatalog;
  private final StaffService staff;

  /** Creates the seeder. */
  public DevDataSeeder(CatalogService catalog, WalletService wallet, PromotionService promotions,
                       PosCatalogService posCatalog, StaffService staff) {
    this.catalog = catalog;
    this.wallet = wallet;
    this.promotions = promotions;
    this.posCatalog = posCatalog;
    this.staff = staff;
  }

  @Override
  public void run(String... args) {
    if (!catalog.categories().isEmpty()) return;
    Category grocery = catalog.createCategory("Grocery", "કરિયાણું", 1, true);
    Category dairy = catalog.createCategory("Dairy", "ડેરી", 2, true);
    Category snacks = catalog.createCategory("Snacks", "નાસ્તો", 3, true);
    Category drinks = catalog.createCategory("Beverages", "પીણાં", 4, true);
    Category care = catalog.createCategory("Personal Care", "પર્સનલ કેર", 5, true);

    AdminProduct basmatiRice = p(grocery, "Basmati Rice", "બાસમતી ચોખા", 14000, 12500, "kg", "1 kg", true, 40);
    AdminProduct toorDal = p(grocery, "Toor Dal", "તુવેર દાળ", 18000, 16500, "kg", "1 kg", false, 30);
    AdminProduct sunflowerOil = p(grocery, "Sunflower Oil", "સનફ્લાવર તેલ", 16000, 14900, "L", "1 L", true, 25);
    AdminProduct amulMilk = p(dairy, "Amul Milk", "અમૂલ દૂધ", 3400, 3300, "pouch", "500 ml", false, 60);
    AdminProduct paneer = p(dairy, "Paneer", "પનીર", 9000, 8200, "pack", "200 g", true, 15);
    AdminProduct potatoChips = p(snacks, "Potato Chips", "બટાકા ચિપ્સ", 2000, 1800, "pack", "90 g", true, 80);
    AdminProduct digestiveBiscuits = p(snacks, "Digestive Biscuits", "બિસ્કિટ", 3500, 3200, "pack", "250 g", false, 50);
    AdminProduct orangeJuice = p(drinks, "Orange Juice", "ઓરેન્જ જ્યુસ", 12000, 10500, "bottle", "1 L", false, 20);
    AdminProduct masalaTea = p(drinks, "Masala Tea", "મસાલા ચા", 22000, 19900, "pack", "250 g", false, 35);
    AdminProduct bathSoap = p(care, "Bath Soap", "સાબુ", 4500, 4000, "pack", "3 pcs", false, 45);
    AdminProduct toothpaste = p(care, "Toothpaste", "ટૂથપેસ્ટ", 11000, 9900, "tube", "150 g", false, 3);
    AdminProduct shampoo = p(care, "Shampoo", "શેમ્પૂ", 18000, 16500, "bottle", "340 ml", true, 18);

    // counter set-up: GST, barcodes (valid EAN-13), one loose item sold by weight, one cashier
    AdminProduct[] packs = {basmatiRice, toorDal, sunflowerOil, amulMilk, paneer, potatoChips, digestiveBiscuits, orangeJuice,
        masalaTea, bathSoap, toothpaste, shampoo};
    int[] gst = {5, 5, 5, 0, 5, 12, 18, 12, 5, 18, 18, 18};
    for (int i = 0; i < packs.length; i++) {
      String body = "89010000" + String.format("%04d", i + 1);
      posCatalog.save(packs[i].id(), SoldBy.UNIT, null, gst[i], "", List.of(body + BarcodeParser.checkDigit(body)), null);
    }
    AdminProduct sugar = p(grocery, "Loose Sugar", "છૂટી ખાંડ", 5000, 4500, "kg", "per kg", false, 25000);
    posCatalog.save(sugar.id(), SoldBy.WEIGHT, "101", 5, "1701", List.of(), null);
    staff.create("cashier@vijaysupermall.local", "Counter 1", null, false, null);

    wallet.createRule(new StoredRule(null, new CoinRule("Spend 1000 get 10", 100000, 10, CoinMode.PRO_RATA, 0),
        null, null, true), null);
    promotions.create(new Coupon(null, "FESTIVE50", Coupon.FLAT, 5000, 99900, 0, null, 0, 0, 0, null, null, true), null);
    log.info("Dev sample data created");
  }

  private AdminProduct p(Category c, String en, String gu, long mrp, long price, String unit, String pack, boolean hot, int qty) {
    return catalog.create(new CatalogService.ProductInput(c.id(), en, gu, "", mrp, price, unit, pack, true, hot,
        (Instant) null, (Instant) null, true, true, qty, 5), null);
  }
}
