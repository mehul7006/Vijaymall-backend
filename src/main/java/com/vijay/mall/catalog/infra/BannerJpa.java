package com.vijay.mall.catalog.infra;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for the {@code hot_banner} table. */
interface BannerJpa extends JpaRepository<HotBannerEntity, UUID> {
  /** Every banner, oldest start first; banners without a start come first. */
  @Query("select b from HotBannerEntity b order by b.startAt asc nulls first")
  List<HotBannerEntity> allOrdered();

  @Query("select b from HotBannerEntity b where b.active = true and (b.startAt is null or b.startAt <= :now) "
      + "and (b.endAt is null or b.endAt >= :now)")
  /** Banners that are switched on and whose start and end (when set) include {@code now}. */
  List<HotBannerEntity> live(@Param("now") Instant now);
}
