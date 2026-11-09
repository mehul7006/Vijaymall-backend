package com.vijay.mall.pos.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.pos.app.CreditService;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.PosReportService;
import com.vijay.mall.pos.app.PosReturnService;
import com.vijay.mall.pos.app.PosService;
import com.vijay.mall.pos.app.PosViews;
import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.WeighedMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The counter endpoints added for the Flutter till: shop round-off mode, quote with discount, receipts, incremental products. */
class PosControllerGapsTest {
  private final PosCatalogService catalog = mock(PosCatalogService.class);
  private final PosService pos = mock(PosService.class);
  private final ShopService shop = mock(ShopService.class);
  private final PosController controller = new PosController(catalog, pos, mock(PosReturnService.class), mock(PosReportService.class), shop);

  @Test void shopAnswersWithTheRoundOffMode() {
    when(shop.get()).thenReturn(new ShopSetting("S", "A", "P", "G", "24", "u@upi", "VSM", "F", "2", WeighedMode.WEIGHT_GRAMS, "pr", "dt"));
    when(pos.roundOffMode()).thenReturn("NEAREST_RUPEE");
    var v = controller.shop();
    assertThat(v.roundOffMode()).isEqualTo("NEAREST_RUPEE");
    assertThat(v.shopName()).isEqualTo("S");
    assertThat(v.upiId()).isEqualTo("u@upi");
    assertThat(v.weighedMode()).isEqualTo(WeighedMode.WEIGHT_GRAMS);
    assertThat(v.promise()).isEqualTo("pr");
    assertThat(v.details()).isEqualTo("dt");
  }

  @Test void quotePassesTheManualDiscountAlong() {
    var md = new PosViews.ManualDiscount(500, null, null);
    var body = new PosController.QuoteBody(List.of(new PosViews.Item(UUID.randomUUID(), 1)), "9000000001", "C", null, 10, md);
    controller.quote(body);
    verify(pos).quote(new PosViews.Cart(body.items(), "9000000001", "C", null, 10), md);
  }

  @Test void productsPassUpdatedSince() {
    Instant since = Instant.parse("2026-10-01T00:00:00Z");
    Page<PosViews.PosProduct> page = new Page<>(List.of(), 0, 20, 0);
    when(catalog.search("rice", 0, 20, since)).thenReturn(page);
    assertThat(controller.products("rice", 0, 20, since)).isSameAs(page);
  }

  @Test void creditNoteReceiptKeepsTheRequestedWidth() {
    UUID sale = UUID.randomUUID();
    UUID ret = UUID.randomUUID();
    when(pos.creditNoteReceipt(sale, ret, 48)).thenReturn("text");
    var r = controller.creditNoteReceipt(sale, ret, 48);
    assertThat(r.width()).isEqualTo(48);
    assertThat(r.text()).isEqualTo("text");
  }

  @Test void repaymentReceiptKeepsTheRequestedWidth() {
    CreditService credit = mock(CreditService.class);
    UUID id = UUID.randomUUID();
    when(credit.receiptText(id, 32)).thenReturn("t");
    var r = new PosCreditController(credit).receipt(id, 32);
    assertThat(r.text()).isEqualTo("t");
    assertThat(r.width()).isEqualTo(32);
  }
}
