package com.vijay.mall.inventory.infra;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for the {@code inventory} table (one stock row per product). */
interface InventoryJpa extends JpaRepository<InventoryEntity, UUID> {
  /** Atomic: takes qty only if enough is on hand (one UPDATE, so two buyers can never both get the last item). */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update InventoryEntity i set i.qtyOnHand = i.qtyOnHand - :q, i.updatedAt = :now "
      + "where i.productId = :p and i.qtyOnHand >= :q")
  int reserve(@Param("p") UUID productId, @Param("q") int qty, @Param("now") Instant now);

  /** Adds delta (may be negative) only if the result stays at zero or above. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update InventoryEntity i set i.qtyOnHand = i.qtyOnHand + :d, i.updatedAt = :now "
      + "where i.productId = :p and i.qtyOnHand + :d >= 0")
  int add(@Param("p") UUID productId, @Param("d") int delta, @Param("now") Instant now);

  /**
   * Sets the low-stock alert level of a product; returns the number of rows changed (0 when the product has no stock row).
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update InventoryEntity i set i.lowThreshold = :l where i.productId = :p")
  int setThreshold(@Param("p") UUID productId, @Param("l") int low);

  /** The stock row with a database lock held until the transaction ends, so nothing changes the quantity while it is being counted. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from InventoryEntity i where i.productId = :p")
  Optional<InventoryEntity> lockById(@Param("p") UUID productId);

  /** Stock rows of these products. */
  @Query("select i from InventoryEntity i where i.productId in :ids")
  List<InventoryEntity> byIds(@Param("ids") Collection<UUID> ids);

  /** Number of products whose stock is at or below their own alert level. */
  @Query("select count(i) from InventoryEntity i where i.qtyOnHand <= i.lowThreshold")
  long countLow();

  /** Products at or below their alert level, emptiest first, limited by the page request. */
  @Query("select i from InventoryEntity i where i.qtyOnHand <= i.lowThreshold order by i.qtyOnHand asc")
  List<InventoryEntity> lowStock(Pageable page);
}
