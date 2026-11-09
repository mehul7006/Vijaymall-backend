package com.vijay.mall.purchasing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.files.FileStore;
import com.vijay.mall.purchasing.infra.PurchaseDocRepository;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import com.vijay.mall.purchasing.infra.PurchaseRepository.Header;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** The branches of invoice attachments and purchase edits that the HTTP tests do not reach (failures and half-filled edits). */
class PurchaseAttachmentAndEditTest {
  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private final UUID actor = UUID.randomUUID();
  private final UUID id = UUID.randomUUID();
  private PurchaseRepository repo;
  private PurchaseDocRepository docs;
  private PurchaseService purchases;
  private FileStore files;
  private TransactionTemplate tx;

  private Header header(String file, String gstin, String state) {
    return new Header(id, UUID.randomUUID(), "Agro", "", gstin, state, false, "REGULAR", "A-1", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), "", "",
        0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, LocalDate.of(2026, 11, 20), 0, 0, 0, "PENDING", null, actor, NOW, file, file == null ? null : "application/pdf");
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @BeforeEach
  void setUp() {
    repo = mock(PurchaseRepository.class);
    docs = mock(PurchaseDocRepository.class);
    purchases = mock(PurchaseService.class);
    files = mock(FileStore.class);
    tx = mock(TransactionTemplate.class);
    when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback) i.getArgument(0)).doInTransaction(null));
  }

  @Test
  void aFailedSaveRemovesTheFileJustWritten() {
    var service = new PurchaseAttachmentService(repo, docs, purchases, files, mock(AuditService.class), tx);
    when(repo.find(id)).thenReturn(Optional.of(header(null, "", "")));
    when(repo.findForUpdate(id)).thenReturn(Optional.of(header(null, "", "")));
    when(files.save(any(), any(), any())).thenReturn("new.pdf");
    doThrow(new IllegalStateException("db down")).when(docs).setAttachment(id, "new.pdf", "application/pdf");
    assertThatThrownBy(() -> service.attach(actor, id, "%PDF-1".getBytes())).isInstanceOf(IllegalStateException.class);
    verify(files).delete("purchase-invoices", "new.pdf");
  }

  @Test
  void aFileMissingFromTheDiskIsNotFoundAndAnUnknownPurchaseIsToo() {
    var service = new PurchaseAttachmentService(repo, docs, purchases, files, mock(AuditService.class), tx);
    when(repo.find(id)).thenReturn(Optional.of(header("old.pdf", "", "")), Optional.empty());
    when(files.read("purchase-invoices", "old.pdf")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.read(id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.read(id)).isInstanceOf(ApiException.class);
    verify(files, never()).delete(any(), any());
  }

  private PurchaseEditService edits() {
    return new PurchaseEditService(repo, docs, purchases, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void aGstinCanBeClearedAndAStateAloneMustAgreeWithTheGstinWeHave() {
    when(repo.findForUpdate(id)).thenReturn(Optional.of(header(null, "24ABCDE1234F1Z5", "24")));
    when(repo.find(id)).thenReturn(Optional.of(header(null, "", "24")));
    Map<String, Object> clear = new HashMap<>();
    clear.put("supplierGstin", null);
    edits().edit(actor, id, clear);
    verify(docs).updateFields(id, "A-1", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20), "", "", "", "24");

    assertThatThrownBy(() -> edits().edit(actor, id, Map.of("supplierStateCode", "27"))).isInstanceOf(ApiException.class);

    when(repo.findForUpdate(id)).thenReturn(Optional.of(header(null, "", "")));
    edits().edit(actor, id, Map.of("supplierStateCode", "27"));
    verify(docs).updateFields(id, "A-1", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20), "", "", "", "27");
    edits().edit(actor, id, Map.of("supplierGstin", ""));
    assertThatThrownBy(() -> edits().edit(actor, id, null)).isInstanceOf(ApiException.class);
  }
}
