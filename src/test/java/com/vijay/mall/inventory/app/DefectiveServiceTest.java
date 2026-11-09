package com.vijay.mall.inventory.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.infra.DefectiveRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DefectiveServiceTest {
  private final Instant now = Instant.parse("2026-11-09T10:00:00Z");
  private final UUID actor = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();
  private final UUID batch = UUID.randomUUID();
  private DefectiveRepository repo;
  private InventoryService inventory;
  private AuditService audit;
  private DefectiveService service;

  @BeforeEach
  void setUp() {
    repo = mock(DefectiveRepository.class);
    inventory = mock(InventoryService.class);
    audit = mock(AuditService.class);
    service = new DefectiveService(repo, inventory, audit, Clock.fixed(now, ZoneOffset.UTC));
  }

  private DefectiveRepository.Row row(String status) {
    return new DefectiveRepository.Row(batch, product, "Juice", 4, "POS_RETURN", null, null, status, now, actor, null, null, null, null, null, null);
  }

  private void batchIs(String status) { when(repo.find(batch)).thenReturn(Optional.of(row(status))); }

  private void assertCode(Runnable r, String code) {
    assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  @Test
  void aClaimIsPutAsideForReviewNotOnTheShelf() {
    UUID ref = UUID.randomUUID();
    service.record(List.of(new InventoryService.Item(product, 2), new InventoryService.Item(UUID.randomUUID(), 0)), "ORDER_RETURN", ref, actor, "  leaking ");
    verify(repo).insert(any(), eq(product), eq(2), eq("ORDER_RETURN"), eq(ref), eq("leaking"), eq(actor), eq(now));
    verify(repo, Mockito.times(1)).insert(any(), any(), anyInt(), anyString(), any(), any(), any(), any());
    service.record(List.of(new InventoryService.Item(product, 1)), "POS_RETURN", ref, actor, "   ");
    service.record(List.of(new InventoryService.Item(product, 1)), "POS_RETURN", ref, actor, null);
    verify(repo, Mockito.times(2)).insert(any(), eq(product), eq(1), eq("POS_RETURN"), eq(ref), eq(null), eq(actor), eq(now));
    verify(inventory, never()).adjust(any(), anyInt(), anyString(), any(), any());
  }

  @Test
  void listChecksTheStatusAndClampsThePaging() {
    when(repo.page(any(), any(), anyInt(), anyInt())).thenReturn(List.of(row("HELD")));
    when(repo.count(any(), any())).thenReturn(1L);
    var p = service.list(" held ", product, -3, 500);
    assertThat(p.page()).isZero();
    assertThat(p.size()).isEqualTo(50);
    assertThat(p.total()).isEqualTo(1);
    verify(repo).page("HELD", product, 0, 50);
    service.list("pending_review", null, 0, 20);
    service.list(null, null, 0, 20);
    service.list("  ", null, 0, 20);
    verify(repo, Mockito.times(2)).page(eq(null), eq(null), eq(0), eq(20));
    assertCode(() -> service.list("NOPE", null, 0, 20), "VALIDATION_FAILED");
  }

  @Test
  void summaryAndUnitCountsComeFromTheLedger() {
    when(repo.openSummary()).thenReturn(List.of(new DefectiveRepository.Summary(product, "Juice", 6, 2, 3)));
    when(repo.heldUnits()).thenReturn(6L);
    when(repo.pendingReviewUnits()).thenReturn(2L);
    assertThat(service.summary()).hasSize(1);
    assertThat(service.heldUnits()).isEqualTo(6);
    assertThat(service.pendingReviewUnits()).isEqualTo(2);
  }

  @Test
  void confirmingDefectiveMovesTheBatchToTheDefectiveListWithoutTouchingStock() {
    batchIs("PENDING_REVIEW");
    when(repo.review(eq(batch), anyString(), any(), eq(actor), eq(now))).thenReturn(true);
    service.review(batch, " confirm_defective ", " crushed ", actor, true);
    verify(repo).review(batch, "HELD", "crushed", actor, now);
    verify(audit).log(actor, "DEFECTIVE_CONFIRM_DEFECTIVE", "defective_stock", batch, "Juice x4");
    verify(inventory, never()).adjust(any(), anyInt(), anyString(), any(), any());
  }

  @Test
  void aClaimThatIsNotDefectiveIsAddedBackToSellableStock() {
    batchIs("PENDING_REVIEW");
    when(repo.review(eq(batch), anyString(), any(), eq(actor), eq(now))).thenReturn(true);
    service.review(batch, "RESTOCK", null, actor, false);
    verify(repo).review(batch, "RESTOCKED", null, actor, now);
    verify(inventory).adjust(product, 4, "DEFECTIVE_REVIEW_RESTOCK", actor, batch);
    verify(audit).log(actor, "DEFECTIVE_RESTOCK", "defective_stock", batch, "Juice x4");
  }

  @Test
  void aReviewNeedsAValidDecisionNoteAndAClaimThatIsStillWaiting() {
    assertCode(() -> service.review(batch, "WRITE_OFF", null, actor, true), "VALIDATION_FAILED");
    assertCode(() -> service.review(batch, null, null, actor, true), "VALIDATION_FAILED");
    assertCode(() -> service.review(batch, "RESTOCK", "x".repeat(201), actor, true), "VALIDATION_FAILED");
    when(repo.find(batch)).thenReturn(Optional.empty());
    assertCode(() -> service.review(batch, "RESTOCK", null, actor, true), "NOT_FOUND");
    batchIs("HELD");
    when(repo.review(eq(batch), anyString(), any(), any(), any())).thenReturn(false);
    assertCode(() -> service.review(batch, "RESTOCK", null, actor, true), "DEFECTIVE_ALREADY_REVIEWED");
    verify(inventory, never()).adjust(any(), anyInt(), anyString(), any(), any());
  }

  @Test
  void aStaffMemberCannotConfirmTheirOwnDefectiveClaimButAnAdminOrAnotherPersonCan() {
    batchIs("PENDING_REVIEW"); // created by `actor`
    when(repo.review(eq(batch), anyString(), any(), any(), eq(now))).thenReturn(true);
    assertCode(() -> service.review(batch, "CONFIRM_DEFECTIVE", null, actor, false), "DEFECTIVE_SELF_REVIEW");
    verify(repo, never()).review(any(), anyString(), any(), any(), any());
    service.review(batch, "CONFIRM_DEFECTIVE", null, UUID.randomUUID(), false); // another person
    service.review(batch, "CONFIRM_DEFECTIVE", null, actor, true);              // the admin may
    service.review(batch, "RESTOCK", null, actor, false);                       // putting goods back on sale is always fine
    verify(repo, Mockito.times(3)).review(eq(batch), anyString(), any(), any(), eq(now));
  }

  @Test
  void aConfirmedBatchCanBeWrittenOffOrSentBackButNeverRestockedFromHere() {
    batchIs("HELD");
    when(repo.resolve(eq(batch), anyString(), any(), eq(actor), eq(now))).thenReturn(true);
    service.resolve(batch, " write_off ", " broken ", actor);
    verify(repo).resolve(batch, "WRITTEN_OFF", "broken", actor, now);
    verify(audit).log(actor, "DEFECTIVE_WRITE_OFF", "defective_stock", batch, "Juice x4");
    service.resolve(batch, "RETURN_TO_SUPPLIER", null, actor);
    verify(repo).resolve(batch, "RETURNED_TO_SUPPLIER", null, actor, now);
    assertCode(() -> service.resolve(batch, "RESTOCK", null, actor), "VALIDATION_FAILED");
    assertCode(() -> service.resolve(batch, null, null, actor), "VALIDATION_FAILED");
    assertCode(() -> service.resolve(batch, "WRITE_OFF", "x".repeat(201), actor), "VALIDATION_FAILED");
    verify(inventory, never()).adjust(any(), anyInt(), anyString(), any(), any());
  }

  @Test
  void anUnknownOrNotYetConfirmedBatchCannotBeResolved() {
    when(repo.find(batch)).thenReturn(Optional.empty());
    assertCode(() -> service.resolve(batch, "WRITE_OFF", null, actor), "NOT_FOUND");
    batchIs("PENDING_REVIEW");
    when(repo.resolve(eq(batch), anyString(), any(), any(), any())).thenReturn(false);
    assertCode(() -> service.resolve(batch, "WRITE_OFF", null, actor), "DEFECTIVE_NOT_HELD");
    verify(audit, never()).log(any(), anyString(), anyString(), any(), any());
  }
}
