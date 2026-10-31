package com.vijay.mall.catalog.infra;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BannerJpa extends JpaRepository<HotBannerEntity, UUID> {
  @Query("select b from HotBannerEntity b order by b.startAt asc nulls first")
  List<HotBannerEntity> allOrdered();

  @Query("select b from HotBannerEntity b where b.active = true and (b.startAt is null or b.startAt <= :now) "
      + "and (b.endAt is null or b.endAt >= :now)")
  List<HotBannerEntity> live(@Param("now") Instant now);
}
