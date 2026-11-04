package com.vijay.mall.pos.infra;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CreditLedgerJpa extends JpaRepository<CreditLedgerEntity, UUID> {
  Page<CreditLedgerEntity> findByUserId(UUID userId, Pageable page);

  /** Credit that earlier returns of one bill already took off the customer's account. */
  @Query("select coalesce(sum(-l.deltaPaise), 0) from CreditLedgerEntity l where l.saleId = :sale and l.kind = 'RETURN'")
  long returnedAgainst(@Param("sale") UUID saleId);
}
