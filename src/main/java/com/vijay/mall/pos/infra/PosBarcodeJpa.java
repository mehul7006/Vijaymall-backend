package com.vijay.mall.pos.infra;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code product_barcode}, where each barcode belongs to one product. */
interface PosBarcodeJpa extends JpaRepository<ProductBarcodeEntity, String> {
  List<ProductBarcodeEntity> findByProductIdOrderByCode(UUID productId);

  /** Deletes all barcodes of a product, used before the new list is saved. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from ProductBarcodeEntity b where b.productId = :p")
  int deleteForProduct(@Param("p") UUID productId);
}
