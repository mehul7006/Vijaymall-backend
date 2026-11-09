package com.vijay.mall.purchasing.infra;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for the {@code purchase} table (supplier deliveries). */
interface PurchaseJpa extends JpaRepository<PurchaseEntity, UUID> {
  /** The delivery that was recorded under this idempotency key, if any. */
  Optional<PurchaseEntity> findByIdemKey(String idemKey);

  /** One page of deliveries, newest first. */
  Page<PurchaseEntity> findAllByOrderByCreatedAtDesc(Pageable page);

  /**
   * Deliveries other than {@code self}, recorded at or before {@code upTo}, with this supplier and invoice number (both compared trimmed
   * and in lower case; the caller passes them already normalised), oldest first. Served by the index {@code purchase_supplier_invoice}.
   */
  @Query("select p from PurchaseEntity p where lower(trim(p.supplierName)) = :supplier and lower(trim(p.invoiceNo)) = :invoice "
      + "and p.invoiceNo <> '' and p.id <> :self and p.createdAt <= :upTo order by p.createdAt asc, p.id asc")
  List<PurchaseEntity> earlierWithInvoice(@Param("supplier") String supplier, @Param("invoice") String invoice, @Param("self") UUID self,
                                          @Param("upTo") Instant upTo, Pageable page);
}
