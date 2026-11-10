package com.vijay.mall.gst.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.gst.domain.GstRow;
import com.vijay.mall.gst.infra.GstRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Admin edits of the master against a mocked repository: audit lines, guards, and when the index is rebuilt. */
class GstAdminServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-10T06:00:00Z");
  private final GstRepository repo = mock(GstRepository.class);
  private final GstService reads = mock(GstService.class);
  private final AuditService audit = mock(AuditService.class);
  private final GstAdminService admin = new GstAdminService(repo, reads, audit, Clock.fixed(NOW, ZoneOffset.UTC), JsonMapper.builder().build());
  private final UUID actor = UUID.randomUUID();

  @AfterEach void clean() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
  }

  private static GstRowInput input(String hsn, String percent, List<String> keywords) {
    return new GstRowInput(hsn, "Test snack", null, new BigDecimal(percent), null, "TAXABLE", null, null, "VERIFIED", null, null, null, keywords);
  }

  private GstRow.Hsn stored(String hsn, boolean active) {
    GstRow.Hsn h = GstFixtures.hsn(hsn, 3, "ANY", "5", "VERIFIED");
    return new GstRow.Hsn(h.id(), h.rowNo(), h.hsn(), h.chapter(), h.tariffDescription(), h.productDescription(), h.nameGu(), h.classificationConditions(),
        h.conditionKey(), h.conditionLabel(), h.gstPercent(), new BigDecimal("2.5"), new BigDecimal("2.5"), h.igstPercent(), h.cessPercent(), h.taxType(),
        h.schedule(), h.notificationReference(), h.sourceReference(), h.effectiveFrom(), h.verification(), h.note(), active, h.updatedAt());
  }

  @Test void createStoresTheRowTheNamesAndBothAuditLinesThenRebuildsTheIndex() {
    when(repo.findHsn(any())).thenAnswer(i -> Optional.of(stored("7777", true)));
    GstService.RowView v = admin.create(input("7777", "5", List.of("my test snack", " ")), actor);
    ArgumentCaptor<GstRow.Hsn> row = ArgumentCaptor.forClass(GstRow.Hsn.class);
    verify(repo).insertHsn(row.capture(), eq(actor), eq(NOW));
    assertThat(row.getValue().hsn()).isEqualTo("7777");
    verify(repo).addKeywordIfNew("my test snack", "7777", actor, NOW);
    verify(repo, times(1)).addKeywordIfNew(anyString(), anyString(), any(), any());
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(repo).audit(eq(actor), eq("CREATE"), eq("HSN_RATE"), eq(row.getValue().id()), isNull(), json.capture(), eq(NOW));
    assertThat(json.getValue()).contains("\"gstPercent\":5", "\"taxType\":\"TAXABLE\"", "\"effectiveFrom\":\"2026-11-10\"", "\"active\":true");
    verify(audit).log(eq(actor), eq("CREATE"), eq("gst_hsn_rate"), eq(row.getValue().id()), eq("hsn 7777 ANY 5% TAXABLE"));
    verify(reads).reload();
    assertThat(v.hsn()).isEqualTo("7777");
  }

  @Test void createWithABadBodyWritesNothing() {
    ApiException e = (ApiException) catchThrowable(() -> admin.create(input("12", "5", null), actor));
    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
    verify(repo, never()).insertHsn(any(), any(), any());
    verify(reads, never()).reload();
  }

  @Test void updateWritesOldAndNewAndRebuildsOnlyAfterTheCommit() {
    GstRow.Hsn old = stored("3305", true);
    when(repo.findHsn(old.id())).thenReturn(Optional.of(old));
    TransactionSynchronizationManager.initSynchronization();
    admin.update(old.id(), input("3305", "18", null), actor);
    verify(reads, never()).reload();
    ArgumentCaptor<String> oldJson = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> newJson = ArgumentCaptor.forClass(String.class);
    verify(repo).audit(eq(actor), eq("UPDATE"), eq("HSN_RATE"), eq(old.id()), oldJson.capture(), newJson.capture(), eq(NOW));
    assertThat(oldJson.getValue()).contains("\"gstPercent\":5");
    assertThat(newJson.getValue()).contains("\"gstPercent\":18");
    verify(audit).log(eq(actor), eq("UPDATE"), eq("gst_hsn_rate"), eq(old.id()), eq("hsn 3305 ANY 5% TAXABLE -> hsn 3305 ANY 18% TAXABLE"));
    TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    verify(reads).reload();
  }

  @Test void aRolledBackEditNeverRebuildsTheIndex() {
    GstRow.Hsn old = stored("3305", true);
    when(repo.findHsn(old.id())).thenReturn(Optional.of(old));
    TransactionSynchronizationManager.initSynchronization();
    admin.update(old.id(), input("3305", "18", null), actor);
    TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    verify(reads, never()).reload();
  }

  @Test void savingASwitchedOffRowSwitchesItOnAndAuditsActivate() {
    GstRow.Hsn old = stored("0401", false);
    when(repo.findHsn(old.id())).thenReturn(Optional.of(old));
    admin.update(old.id(), input("0401", "0", null), actor);
    verify(repo).audit(eq(actor), eq("ACTIVATE"), eq("HSN_RATE"), eq(old.id()), anyString(), anyString(), eq(NOW));
    ArgumentCaptor<GstRow.Hsn> saved = ArgumentCaptor.forClass(GstRow.Hsn.class);
    verify(repo).updateHsn(saved.capture(), eq(actor), eq(NOW));
    assertThat(saved.getValue().active()).isTrue();
  }

  @Test void updateOfAnUnknownRowIs404() {
    UUID id = UUID.randomUUID();
    when(repo.findHsn(id)).thenReturn(Optional.empty());
    ApiException e = (ApiException) catchThrowable(() -> admin.update(id, input("3305", "5", null), actor));
    assertThat(e.code()).isEqualTo("NOT_FOUND");
    ApiException d = (ApiException) catchThrowable(() -> admin.deactivate(id, actor));
    assertThat(d.status().value()).isEqualTo(404);
  }

  @Test void changingTheCodeOfTheLastRowThatItemNamesPointToIs409() {
    GstRow.Hsn old = stored("2501", true);
    when(repo.findHsn(old.id())).thenReturn(Optional.of(old));
    when(repo.keywordsPointTo("2501")).thenReturn(true);
    when(repo.otherActiveRow("2501", old.id())).thenReturn(false);
    ApiException e = (ApiException) catchThrowable(() -> admin.update(old.id(), input("2502", "5", null), actor));
    assertThat(e.code()).isEqualTo("GST_HSN_IN_USE");
    assertThat(e.status().value()).isEqualTo(409);
    verify(repo, never()).updateHsn(any(), any(), any());
  }

  @Test void changingTheCodeIsFineWhenAnotherActiveRowOrNoItemNamesRemain() {
    GstRow.Hsn old = stored("2501", true);
    when(repo.findHsn(old.id())).thenReturn(Optional.of(old));
    when(repo.keywordsPointTo("2501")).thenReturn(true);
    when(repo.otherActiveRow("2501", old.id())).thenReturn(true);
    admin.update(old.id(), input("2502", "5", null), actor);
    when(repo.keywordsPointTo("2501")).thenReturn(false);
    when(repo.otherActiveRow("2501", old.id())).thenReturn(false);
    admin.update(old.id(), input("2503", "5", null), actor);
    verify(repo, times(2)).updateHsn(any(), eq(actor), eq(NOW));
  }

  @Test void deactivateSwitchesOffAuditsAndRebuilds() {
    GstRow.Hsn old = stored("0401", true);
    when(repo.findHsn(old.id())).thenReturn(Optional.of(old));
    when(repo.keywordsPointTo("0401")).thenReturn(true);
    when(repo.otherActiveRow("0401", old.id())).thenReturn(true);
    admin.deactivate(old.id(), actor);
    ArgumentCaptor<GstRow.Hsn> off = ArgumentCaptor.forClass(GstRow.Hsn.class);
    verify(repo).updateHsn(off.capture(), eq(actor), eq(NOW));
    assertThat(off.getValue().active()).isFalse();
    verify(repo).audit(eq(actor), eq("DEACTIVATE"), eq("HSN_RATE"), eq(old.id()), anyString(), anyString(), eq(NOW));
    verify(audit).log(eq(actor), eq("DEACTIVATE"), eq("gst_hsn_rate"), eq(old.id()), eq("hsn 0401 ANY 5% TAXABLE"));
    verify(reads).reload();
  }

  @Test void deactivatingAnAlreadyOffRowDoesNothing() {
    GstRow.Hsn old = stored("0401", false);
    when(repo.findHsn(old.id())).thenReturn(Optional.of(old));
    admin.deactivate(old.id(), actor);
    verify(repo, never()).updateHsn(any(), any(), any());
    verify(reads, never()).reload();
  }

  @Test void deactivatingTheOnlyRowOfAUsedCodeIs409() {
    GstRow.Hsn old = stored("2501", true);
    when(repo.findHsn(old.id())).thenReturn(Optional.of(old));
    when(repo.keywordsPointTo("2501")).thenReturn(true);
    ApiException e = (ApiException) catchThrowable(() -> admin.deactivate(old.id(), actor));
    assertThat(e.code()).isEqualTo("GST_HSN_IN_USE");
    assertThat(e.details()).containsEntry("hsn", "2501");
    verify(repo, never()).updateHsn(any(), any(), any());
  }

  @Test void aFailedRebuildAfterTheEditIsSwallowedAndLeftToTheNextCheck() {
    GstRow.Hsn old = stored("0401", true);
    when(repo.findHsn(old.id())).thenReturn(Optional.of(old));
    doThrow(new IllegalStateException("db down")).when(reads).reload();
    admin.update(old.id(), input("0401", "0", null), actor);
    verify(reads).reload();
  }
}
