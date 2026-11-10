package com.vijay.mall.pos.infra;

import com.vijay.mall.pos.domain.GstRates;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.WeighedMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Repository branches that need odd stored values or empty results, with the Spring Data interfaces mocked. */
class PosInfraCoverageTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  // ----- shop settings -----
  @Test void shopSettingWithNullPromiseAndDetails_readsAsEmptyText() {
    PosShopJpa jpa = mock(PosShopJpa.class);
    ShopSettingEntity e = new ShopSettingEntity();
    e.shopName = "S"; e.address = "A"; e.phone = "P"; e.gstin = ""; e.stateCode = "24"; e.upiId = ""; e.invoicePrefix = "VSM";
    e.receiptFooter = "F"; e.weighedPrefix = "2"; e.weighedMode = "WEIGHT_GRAMS";
    when(jpa.findById(1)).thenReturn(Optional.of(e));
    ShopSetting s = new ShopSettingRepository(jpa).get();
    assertThat(s.promise()).isEmpty();
    assertThat(s.details()).isEmpty();
    assertThat(s.weighedMode()).isEqualTo(WeighedMode.WEIGHT_GRAMS);
    e.promise = "Fast";
    e.details = "About";
    assertThat(new ShopSettingRepository(jpa).get().promise()).isEqualTo("Fast");
    assertThat(new ShopSettingRepository(jpa).get().details()).isEqualTo("About");
  }

  @Test void savingNullPromiseAndDetails_storesEmptyText() {
    PosShopJpa jpa = mock(PosShopJpa.class);
    ShopSettingEntity e = new ShopSettingEntity();
    when(jpa.findById(1)).thenReturn(Optional.of(e));
    new ShopSettingRepository(jpa).save(new ShopSetting("S", "A", "P", "", "24", "", "VSM", "F", "2", WeighedMode.PRICE_PAISE, null, null), NOW);
    assertThat(e.promise).isEmpty();
    assertThat(e.details).isEmpty();
    assertThat(e.weighedMode).isEqualTo("PRICE_PAISE");
    assertThat(e.updatedAt).isEqualTo(NOW);
    new ShopSettingRepository(jpa).save(new ShopSetting("S", "A", "P", "", "24", "", "VSM", "F", "2", WeighedMode.PRICE_PAISE, "p", "d"), NOW);
    assertThat(e.promise).isEqualTo("p");
    assertThat(e.details).isEqualTo("d");
  }

  // ----- credit -----
  private final CreditJpa accounts = mock(CreditJpa.class);
  private final CreditLedgerJpa ledgerJpa = mock(CreditLedgerJpa.class);
  private final CreditPaymentJpa payments = mock(CreditPaymentJpa.class);
  private final CreditPaymentLineJpa lines = mock(CreditPaymentLineJpa.class);
  private final CreditRepository credit = new CreditRepository(accounts, ledgerJpa, payments, lines);

  @Test void creditBalanceCanNeverGoBelowZero() {
    UUID user = UUID.randomUUID();
    CreditAccountEntity a = new CreditAccountEntity();
    a.userId = user;
    a.balancePaise = 100;
    when(accounts.lockById(user)).thenReturn(Optional.of(a));
    assertThatThrownBy(() -> credit.append(user, "PAYMENT", -101, "R1", null, null, null, "x", null, NOW))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("below zero");
    assertThat(a.balancePaise).isEqualTo(100);
    verify(ledgerJpa, never()).save(any());
  }

  @Test void creditEntryWithoutNote_isStoredWithEmptyNote() {
    UUID user = UUID.randomUUID();
    CreditAccountEntity a = new CreditAccountEntity();
    a.userId = user;
    a.balancePaise = 100;
    when(accounts.lockById(user)).thenReturn(Optional.of(a));
    when(ledgerJpa.save(any())).thenAnswer(i -> i.getArgument(0));
    var entry = credit.append(user, "SALE", 50, "INV1", null, null, null, null, null, NOW);
    assertThat(entry.note()).isEmpty();
    assertThat(entry.balanceAfterPaise()).isEqualTo(150);
    assertThat(a.balancePaise).isEqualTo(150);
    assertThat(a.updatedAt).isEqualTo(NOW);
    var withNote = credit.append(user, "ADJUST", -10, null, null, null, null, "fix", null, NOW);
    assertThat(withNote.note()).isEqualTo("fix");
    assertThat(a.balancePaise).isEqualTo(140);
  }

  @Test void repaymentWithoutNote_isStoredWithEmptyNote() {
    UUID id = UUID.randomUUID();
    credit.insertRepayment(id, "RC1", "k", UUID.randomUUID(), UUID.randomUUID(), 500, null, NOW,
        List.of(new CreditRepository.Line("CASH", 500, null)));
    ArgumentCaptor<CreditPaymentEntity> c = ArgumentCaptor.forClass(CreditPaymentEntity.class);
    verify(payments).saveAndFlush(c.capture());
    assertThat(c.getValue().note).isEmpty();
    credit.insertRepayment(UUID.randomUUID(), "RC2", "k2", UUID.randomUUID(), UUID.randomUUID(), 500, "by hand", NOW, List.of());
    verify(payments, Mockito.times(2)).saveAndFlush(c.capture());
    assertThat(c.getValue().note).isEqualTo("by hand");
  }

  // ----- counter attributes and barcodes -----
  @Test void counterAttributesWithoutHsn_areStoredAsEmpty() {
    PosProductJpa pos = mock(PosProductJpa.class);
    PosBarcodeJpa barcodes = mock(PosBarcodeJpa.class);
    UUID p = UUID.randomUUID();
    when(pos.findById(p)).thenReturn(Optional.empty());
    new PosCatalogRepository(pos, barcodes).save(new ProductPos(p, SoldBy.UNIT, null, GstRates.of(5), null), NOW);
    ArgumentCaptor<ProductPosEntity> c = ArgumentCaptor.forClass(ProductPosEntity.class);
    verify(pos).saveAndFlush(c.capture());
    assertThat(c.getValue().hsn).isEmpty();
    assertThat(c.getValue().productId).isEqualTo(p);
    assertThat(c.getValue().soldBy).isEqualTo("UNIT");
  }

  @Test void attributesOfNoProducts_isEmpty_andUnconfiguredProductsGetDefaults() {
    PosProductJpa pos = mock(PosProductJpa.class);
    var repo = new PosCatalogRepository(pos, mock(PosBarcodeJpa.class));
    assertThat(repo.forProducts(List.of())).isEmpty();
    verify(pos, never()).byIds(any());
    UUID p = UUID.randomUUID();
    when(pos.byIds(any())).thenReturn(List.of());
    var found = repo.forProducts(List.of(p, p));
    assertThat(found).containsOnlyKeys(p);
    assertThat(found.get(p)).isEqualTo(ProductPos.defaults(p));
  }

  @Test void barcodeOfAnotherProduct_isReturned_andNothingIsReplaced() {
    PosProductJpa pos = mock(PosProductJpa.class);
    PosBarcodeJpa barcodes = mock(PosBarcodeJpa.class);
    UUID mine = UUID.randomUUID(), other = UUID.randomUUID();
    ProductBarcodeEntity theirs = new ProductBarcodeEntity();
    theirs.code = "ABCD1234";
    theirs.productId = other;
    ProductBarcodeEntity own = new ProductBarcodeEntity();
    own.code = "OWN-0001";
    own.productId = mine;
    when(barcodes.findById("ABCD1234")).thenReturn(Optional.of(theirs));
    when(barcodes.findById("OWN-0001")).thenReturn(Optional.of(own));
    var repo = new PosCatalogRepository(pos, barcodes);
    assertThat(repo.replaceBarcodes(mine, List.of("OWN-0001", "ABCD1234"), NOW)).contains("ABCD1234");
    verify(barcodes, never()).deleteForProduct(any());
    // a code that already belongs to the same product is not a clash
    assertThat(repo.replaceBarcodes(mine, List.of("OWN-0001"), NOW)).isEmpty();
    verify(barcodes).deleteForProduct(mine);
  }

  // ----- holds and shifts -----
  @Test void discardingAHeldBasketThatDoesNotExist_returnsFalse() {
    PosHoldJpa jpa = mock(PosHoldJpa.class);
    UUID id = UUID.randomUUID();
    when(jpa.existsById(id)).thenReturn(false);
    assertThat(new PosHoldRepository(jpa).delete(id)).isFalse();
    verify(jpa, never()).deleteById(any());
    UUID real = UUID.randomUUID();
    when(jpa.existsById(real)).thenReturn(true);
    assertThat(new PosHoldRepository(jpa).delete(real)).isTrue();
    verify(jpa).deleteById(real);
  }

  @Test void shiftListWithoutCashier_listsEveryCashier() {
    PosShiftJpa jpa = mock(PosShiftJpa.class);
    PosShiftEntity e = new PosShiftEntity();
    e.id = UUID.randomUUID();
    e.cashierId = UUID.randomUUID();
    e.openedAt = NOW;
    when(jpa.findAllByOrderByOpenedAtDesc(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(e)));
    var page = new PosShiftRepository(jpa).page(null, 0, 20);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).open()).isTrue();
    assertThat(page.total()).isEqualTo(1);
    verify(jpa, never()).findByCashierIdOrderByOpenedAtDesc(any(), any());
    when(jpa.findByCashierIdOrderByOpenedAtDesc(any(), any(Pageable.class))).thenReturn(Page.empty());
    assertThat(new PosShiftRepository(jpa).page(UUID.randomUUID(), 0, 20).items()).isEmpty();
  }

  // ----- bills -----
  private final PosSaleJpa sales = mock(PosSaleJpa.class);
  private final PosReturnJpa returns = mock(PosReturnJpa.class);
  private final PosSaleItemJpa items = mock(PosSaleItemJpa.class);
  private final PosSaleRepository saleRepo = new PosSaleRepository(sales, items, mock(PosPaymentJpa.class), returns,
      mock(PosReturnItemJpa.class), mock(PosRefundJpa.class));

  @Test void returningMoreThanWasSold_isReportedAsFalse() {
    UUID item = UUID.randomUUID();
    when(items.addReturned(item, 5)).thenReturn(0);
    when(items.addReturned(item, 1)).thenReturn(1);
    assertThat(saleRepo.addReturned(item, 5)).isFalse();
    assertThat(saleRepo.addReturned(item, 1)).isTrue();
  }

  @Test void documentNumbersOfNoIds_isEmpty_andSkipsTheQueries() {
    assertThat(saleRepo.documentNumbers(List.of())).isEmpty();
    verify(sales, never()).invoiceNumbers(any());
    verify(returns, never()).creditNoteNumbers(any());
  }

  @Test void documentNumbers_mergeBillsAndCreditNotes() {
    UUID bill = UUID.randomUUID(), ret = UUID.randomUUID();
    when(sales.invoiceNumbers(any())).thenReturn(List.<Object[]>of(new Object[] {bill, "VSM/1"}));
    when(returns.creditNoteNumbers(any())).thenReturn(List.<Object[]>of(new Object[] {ret, "VSM-CN/1"}));
    assertThat(saleRepo.documentNumbers(List.of(bill, ret))).containsEntry(bill, "VSM/1").containsEntry(ret, "VSM-CN/1");
  }

  @Test void searchesThatFindNothing_giveEmptyLists() {
    when(sales.findTop50ByCustomerMobileOrderByCreatedAtDesc("9000000000")).thenReturn(List.of());
    when(sales.findTop200ByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    assertThat(saleRepo.byMobile("9000000000")).isEmpty();
    assertThat(saleRepo.between(NOW, NOW.plusSeconds(60))).isEmpty();
    verify(items, never()).findBySaleIdIn(any());
  }

  @Test void thePickerListsCustomersWithALimitByNameAndAllCustomersWhenAsked() {
    jakarta.persistence.EntityManager em = mock(jakarta.persistence.EntityManager.class);
    org.springframework.test.util.ReflectionTestUtils.setField(credit, "em", em);
    jakarta.persistence.Query q = mock(jakarta.persistence.Query.class);
    when(em.createNativeQuery(any(String.class))).thenReturn(q);
    when(q.setParameter(any(String.class), any())).thenReturn(q);
    UUID asObject = UUID.randomUUID(), asText = UUID.randomUUID();
    when(q.getResultList()).thenReturn(List.<Object[]>of(new Object[] {asObject, "Asha", "9000000001", 500L, 1000L, false},
        new Object[] {asText.toString(), null, null, 0L, 0L, true}));

    var withLimit = credit.pick("%as%", false, 0, 10);
    assertThat(withLimit.items()).extracting(CreditRepository.Pick::userId).containsExactly(asObject, asText);
    assertThat(withLimit.items().get(0).limitPaise()).isEqualTo(1000);
    assertThat(withLimit.items().get(1).blocked()).isTrue();
    assertThat(withLimit.total()).isEqualTo(2);
    verify(q).setParameter("pattern", "%as%");
    verify(q).setParameter("n", 11);
    verify(em).createNativeQuery(org.mockito.ArgumentMatchers.contains("join credit_account a on a.user_id = u.id where a.limit_paise > 0 and ("));

    credit.pick(null, true, 2, 5);
    verify(em).createNativeQuery(org.mockito.ArgumentMatchers.contains("left join credit_account a on a.user_id = u.id where u.role = 'CUSTOMER' order by"));
    verify(q).setParameter("o", 10L);
  }
}
