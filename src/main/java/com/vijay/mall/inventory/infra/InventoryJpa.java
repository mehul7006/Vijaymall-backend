package com.vijay.mall.inventory.infra;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update InventoryEntity i set i.lowThreshold = :l where i.productId = :p")
  int setThreshold(@Param("p") UUID productId, @Param("l") int low);

  @Query("select i from InventoryEntity i where i.productId in :ids")
  List<InventoryEntity> byIds(@Param("ids") Collection<UUID> ids);

  @Query("select count(i) from InventoryEntity i where i.qtyOnHand <= i.lowThreshold")
  long countLow();

  @Query("select i from InventoryEntity i where i.qtyOnHand <= i.lowThreshold order by i.qtyOnHand asc")
  List<InventoryEntity> lowStock(Pageable page);
}
