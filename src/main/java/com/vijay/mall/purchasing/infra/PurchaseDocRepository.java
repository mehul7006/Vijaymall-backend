package com.vijay.mall.purchasing.infra;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes the document fields of a recorded purchase that do not touch stock or money: the invoice details, notes, GSTIN text and attachment. */
@Repository
public class PurchaseDocRepository {
  private final JdbcClient jdbc;

  PurchaseDocRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  /** Replaces the editable text and date fields (the caller passes the merged values). */
  public void updateFields(UUID id, String invoiceNo, LocalDate invoiceDate, LocalDate dueDate, String note, String attachmentNote, String gstin,
                           String stateCode) {
    jdbc.sql("""
        update purchase set invoice_no = :invoiceNo, invoice_date = :invoiceDate, due_date = :dueDate, note = :note, attachment_note = :attachmentNote,
          supplier_gstin_override = :gstin, supplier_state_code = :state where id = :id""")
        .param("id", id).param("invoiceNo", invoiceNo).param("invoiceDate", invoiceDate).param("dueDate", dueDate, java.sql.Types.DATE)
        .param("note", note).param("attachmentNote", attachmentNote).param("gstin", gstin).param("state", stateCode).update();
  }

  /** Sets (or with nulls removes) the attached invoice file. */
  public void setAttachment(UUID id, String file, String type) {
    jdbc.sql("update purchase set attachment_file = :f, attachment_type = :t where id = :id")
        .param("f", file, java.sql.Types.VARCHAR).param("t", type, java.sql.Types.VARCHAR).param("id", id).update();
  }
}
