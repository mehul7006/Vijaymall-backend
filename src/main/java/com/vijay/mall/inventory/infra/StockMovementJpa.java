package com.vijay.mall.inventory.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for the {@code stock_movement} table. */
interface StockMovementJpa extends JpaRepository<StockMovementEntity, UUID> {
  /** One page of the stock history of a single product. */
  Page<StockMovementEntity> findByProductId(UUID productId, Pageable page);

  /** One page of the stock history of every product, without counting (the total comes from the shared list totals). */
  List<StockMovementEntity> findPageBy(Pageable page);

  /** One page of the stock history of one product, without counting. */
  List<StockMovementEntity> findPageByProductId(UUID productId, Pageable page);

  /** Counts stock movements but stops at {@code limit}: the cost has a ceiling however long the history grows. */
  @Query(value = "select count(*) from (select 1 from stock_movement limit :limit) c", nativeQuery = true)
  long countUpTo(@Param("limit") int limit);

  /** Counts the stock movements of one product, stopping at {@code limit}. */
  @Query(value = "select count(*) from (select 1 from stock_movement where product_id = :p limit :limit) c", nativeQuery = true)
  long countUpToByProduct(@Param("p") UUID productId, @Param("limit") int limit);
}
