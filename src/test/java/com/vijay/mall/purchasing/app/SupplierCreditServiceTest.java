package com.vijay.mall.purchasing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import com.vijay.mall.purchasing.infra.SupplierLedgerRepository;
import com.vijay.mall.purchasing.infra.SupplierLedgerRepository.Due;
import com.vijay.mall.purchasing.infra.SupplierLedgerRepository.Note;
import com.vijay.mall.purchasing.infra.SupplierRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** The credit rules that need a database race to reach: a credit that changed under the request, and how a refund walks the credit purchases. */
class SupplierCreditServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private final UUID actor = UUID.randomUUID();
  private final UUID supplierId = UUID.randomUUID();
  private SupplierLedgerRepository repo;
  private SupplierRepository suppliers;
  private PurchaseRepository purchaseRepo;
  private PurchaseService purchases;
  private SupplierCreditService service;

  @SuppressWarnings({"unchecked", "rawtypes"})
  @BeforeEach
  void setUp() {
    repo = mock(SupplierLedgerRepository.class);
    suppliers = mock(SupplierRepository.class);
    purchaseRepo = mock(PurchaseRepository.class);
    purchases = mock(PurchaseService.class);
    var tx = mock(TransactionTemplate.class);
    when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback) i.getArgument(0)).doInTransaction(null));
    var ops = mock(AdminOps.class);
    when(ops.once(any(), any(), any(), any(), any(), any(), any())).thenAnswer(i -> ((Supplier) i.getArgument(4)).get());
    service = new SupplierCreditService(repo, suppliers, purchaseRepo, purchases, mock(AuditService.class), tx, Clock.fixed(NOW, ZoneOffset.UTC), ops);
    when(repo.lockSupplier(supplierId)).thenReturn(true);
  }

  @Test
  void aRefundTakesOnlyAsMuchAsItNeedsFromTheOldestCreditAndStopsThere() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(repo.lockCreditPurchases(supplierId)).thenReturn(List.of(new Due(a, supplierId, -300), new Due(b, supplierId, -200)));
    when(repo.useCredit(any(), anyLong())).thenReturn(true);
    var in = new SupplierCreditService.RefundInput(100, "cash", null, null, null);
    var view = service.refund(actor, "k", supplierId, in);
    verify(repo).useCredit(a, 100);
    verify(repo, never()).useCredit(eq(b), anyLong());
    assertThat(view.mode()).isEqualTo("CASH");
    assertThat(view.reference()).isEmpty();

    service.refund(actor, "k2", supplierId, new SupplierCreditService.RefundInput(450, "BANK", " r ", NOW, null));
    verify(repo).useCredit(a, 300);
    verify(repo).useCredit(b, 150);
  }

  @Test
  void aCreditThatChangedUnderTheRequestIsRefusedInsteadOfBeingUsedTwice() {
    UUID a = UUID.randomUUID();
    when(repo.lockCreditPurchases(supplierId)).thenReturn(List.of(new Due(a, supplierId, -300)));
    when(repo.useCredit(a, 100)).thenReturn(false);
    assertThatThrownBy(() -> service.refund(actor, "k", supplierId, new SupplierCreditService.RefundInput(100, "CASH", null, null, null)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CREDIT_EXCEEDS_AVAILABLE"));
    verify(repo, never()).insertRefund(any());

    UUID target = UUID.randomUUID();
    UUID source = UUID.randomUUID();
    UUID noteId = UUID.randomUUID();
    when(repo.supplierOf(target)).thenReturn(Optional.of(supplierId));
    when(repo.lockNote(noteId)).thenReturn(Optional.of(new Note(noteId, source, supplierId, "DN-1", 500, 0)));
    when(repo.lockPurchases(List.of(target, source))).thenReturn(Map.of(target, new Due(target, supplierId, 400), source, new Due(source, supplierId, -500)));
    when(repo.useCredit(source, 100)).thenReturn(false);
    var pay = new PurchaseService.PaymentInput(100, "CREDIT_NOTE", null, null, null, null, noteId);
    assertThatThrownBy(() -> service.payWithCredit(actor, "k", target, pay))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CREDIT_EXCEEDS_AVAILABLE"));
    verify(purchaseRepo, never()).insertPayment(any());
  }

  @Test
  void aNoteOfWhichTheCreditWasAlreadyRefundedGivesNothing() {
    UUID target = UUID.randomUUID();
    UUID source = UUID.randomUUID();
    UUID noteId = UUID.randomUUID();
    when(repo.supplierOf(target)).thenReturn(Optional.of(supplierId));
    when(repo.lockNote(noteId)).thenReturn(Optional.of(new Note(noteId, source, supplierId, "DN-1", 500, 0)));
    when(repo.lockPurchases(List.of(target, source))).thenReturn(Map.of(target, new Due(target, supplierId, 400), source, new Due(source, supplierId, 50)));
    var pay = new PurchaseService.PaymentInput(1, "CREDIT_NOTE", null, null, null, null, noteId);
    assertThatThrownBy(() -> service.payWithCredit(actor, "k", target, pay)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo("CREDIT_EXCEEDS_AVAILABLE");
      assertThat(e.details()).containsEntry("availablePaise", 0L);
    });
  }

  @Test
  void aNegativePageIsTheFirstPageAndAKeyIsRequired() {
    when(suppliers.find(supplierId)).thenReturn(Optional.of(new SupplierRepository.Supplier(supplierId, "S", "s", "", "", "", "", "", "", 0, true, NOW, NOW)));
    when(repo.totals(supplierId, null, null)).thenReturn(new SupplierLedgerRepository.Totals(0, 0));
    when(repo.statement(supplierId, null, null, 0, 0, 50)).thenReturn(new Page<>(List.of(), 0, 50, 0));
    assertThat(service.statement(supplierId, null, null, -3, 500).rows().page()).isZero();
    assertThatThrownBy(() -> service.refund(actor, " ", supplierId, new SupplierCreditService.RefundInput(1, "CASH", null, null, null)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.payWithCredit(actor, " ", UUID.randomUUID(), new PurchaseService.PaymentInput(1, "CREDIT_NOTE", null, null, null, null, null)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void aRefundInCreditNoteModeAnswersThePlainMessageAndAnOtherBadModeListsTheModes() {
    assertThatThrownBy(() -> service.refund(actor, "k", supplierId, new SupplierCreditService.RefundInput(1, " credit_note ", null, null, null)))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.getMessage()).isEqualTo("CREDIT_NOTE is not allowed for refunds");
          assertThat(e.details()).extractingByKey("fields").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
              .containsEntry("mode", "CREDIT_NOTE is not allowed for refunds");
        });
    assertThatThrownBy(() -> service.refund(actor, "k", supplierId, new SupplierCreditService.RefundInput(1, "BARTER", null, null, null)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).isEqualTo("must be CASH, UPI, CARD, BANK or CHEQUE"));
  }

  @Test
  void creditNotesShowWhatIsUsedAndWhatTheCreditUseApiWouldStillAllow() {
    when(suppliers.find(supplierId)).thenReturn(Optional.of(new SupplierRepository.Supplier(supplierId, "S", "s", "", "", "", "", "", "", 0, true, NOW, NOW)));
    UUID purchase = UUID.randomUUID();
    var open = new SupplierLedgerRepository.NoteRow(UUID.randomUUID(), "DN-2", purchase, "INV-1", NOW, 500, 100, -300);
    var gone = new SupplierLedgerRepository.NoteRow(UUID.randomUUID(), "DN-1", purchase, "INV-1", NOW.minusSeconds(60), 500, 0, 50);
    when(repo.notes(supplierId, 0, 50)).thenReturn(new Page<>(List.of(open, gone), 0, 50, 2));
    var page = service.creditNotes(supplierId, -1, 500);
    assertThat(page.items()).extracting(SupplierCreditService.CreditNoteView::number, SupplierCreditService.CreditNoteView::usedPaise,
        SupplierCreditService.CreditNoteView::availablePaise).containsExactly(org.assertj.core.groups.Tuple.tuple("DN-2", 100L, 300L),
        org.assertj.core.groups.Tuple.tuple("DN-1", 0L, 0L));
    when(suppliers.find(supplierId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.creditNotes(supplierId, 0, 10)).isInstanceOf(ApiException.class);
  }
}
