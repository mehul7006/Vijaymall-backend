package com.vijay.mall.purchasing.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.files.FileKind;
import com.vijay.mall.common.files.FileStore;
import com.vijay.mall.purchasing.app.PurchaseService.PurchaseView;
import com.vijay.mall.purchasing.domain.Rounding;
import com.vijay.mall.purchasing.infra.PurchaseDocRepository;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import com.vijay.mall.purchasing.infra.PurchaseRepository.Header;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The invoice picture or PDF of a purchase: one file per purchase, kept in the private file store (never under {@code /img}) and served only
 * through the admin endpoint. The kind is decided by the first bytes of the file. Replacing or removing the file deletes the old one only after
 * the database change is committed, and a failed change removes the file that was just written.
 */
@Service
public class PurchaseAttachmentService {
  /** The private folder the invoice files live in. */
  static final String FOLDER = "purchase-invoices";
  /** Largest accepted file (the web layer refuses bigger uploads with the same code). */
  public static final int MAX_BYTES = 5 * 1024 * 1024;

  /** A stored file and the type to serve it with. */
  public record Attachment(byte[] content, String contentType, String fileName) {}

  private final PurchaseRepository repo;
  private final PurchaseDocRepository docs;
  private final PurchaseService purchases;
  private final FileStore files;
  private final AuditService audit;
  private final TransactionTemplate tx;

  /** Creates the service. */
  public PurchaseAttachmentService(PurchaseRepository repo, PurchaseDocRepository docs, PurchaseService purchases, FileStore files, AuditService audit,
                                   TransactionTemplate tx) {
    this.repo = repo;
    this.docs = docs;
    this.purchases = purchases;
    this.files = files;
    this.audit = audit;
    this.tx = tx;
  }

  /**
   * Attaches (or replaces) the invoice file.
   *
   * @throws ApiException 404 unknown purchase; 400 {@code VALIDATION_FAILED} empty file, {@code FILE_TOO_LARGE} over 5 MB,
   *     {@code UNSUPPORTED_FILE} when it is not a PDF, JPEG, PNG or WebP
   */
  public PurchaseView attach(UUID actor, UUID purchaseId, byte[] content) {
    if (content == null || content.length == 0) throw ApiException.validation("file", "file is empty");
    if (content.length > MAX_BYTES) throw ApiException.fieldError("FILE_TOO_LARGE", "file", "must be 5 MB or smaller");
    FileKind kind = FileKind.detect(content).orElseThrow(() -> ApiException.fieldError("UNSUPPORTED_FILE", "file", "must be a PDF, JPEG, PNG or WebP file"));
    repo.find(purchaseId).orElseThrow(() -> ApiException.notFound("Purchase"));
    String name = files.save(FOLDER, content, kind);
    String old;
    try {
      old = tx.execute(s -> {
        Header h = repo.findForUpdate(purchaseId).orElseThrow(() -> ApiException.notFound("Purchase"));
        docs.setAttachment(purchaseId, name, kind.contentType());
        audit.log(actor, "PURCHASE_ATTACHMENT", "purchase", purchaseId, (h.attachmentFile() == null ? "none" : h.attachmentFile()) + " -> " + name);
        return h.attachmentFile();
      });
    } catch (RuntimeException e) {
      files.delete(FOLDER, name);
      throw e;
    }
    if (old != null) files.delete(FOLDER, old);
    return purchases.get(purchaseId, Rounding.PAISE);
  }

  /** Removes the invoice file; nothing happens when there is none. Answers with the purchase. */
  public PurchaseView remove(UUID actor, UUID purchaseId) {
    String old = tx.execute(s -> {
      Header h = repo.findForUpdate(purchaseId).orElseThrow(() -> ApiException.notFound("Purchase"));
      if (h.attachmentFile() == null) return null;
      docs.setAttachment(purchaseId, null, null);
      audit.log(actor, "PURCHASE_ATTACHMENT", "purchase", purchaseId, h.attachmentFile() + " -> none");
      return h.attachmentFile();
    });
    if (old != null) files.delete(FOLDER, old);
    return purchases.get(purchaseId, Rounding.PAISE);
  }

  /** The invoice file with its type; 404 when the purchase has none (or the file is gone from the disk). */
  public Attachment read(UUID purchaseId) {
    Header h = repo.find(purchaseId).orElseThrow(() -> ApiException.notFound("Purchase"));
    if (h.attachmentFile() == null) throw ApiException.notFound("Attachment");
    byte[] content = files.read(FOLDER, h.attachmentFile()).orElseThrow(() -> ApiException.notFound("Attachment"));
    return new Attachment(content, h.attachmentType(), "invoice-" + purchaseId + h.attachmentFile().substring(h.attachmentFile().lastIndexOf('.')));
  }
}
