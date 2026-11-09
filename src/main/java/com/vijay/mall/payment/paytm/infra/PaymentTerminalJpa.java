package com.vijay.mall.payment.paytm.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code payment_terminal}. */
public interface PaymentTerminalJpa extends JpaRepository<PaymentTerminalEntity, UUID> {
  /** Active machines of a vendor, by name. */
  List<PaymentTerminalEntity> findByProviderAndActiveTrueOrderByNameAsc(String provider);

  /** Whether a machine with this vendor id exists. */
  boolean existsByProviderAndTerminalId(String provider, String terminalId);

  /** All machines, newest first, one page. */
  Page<PaymentTerminalEntity> findAllByOrderByCreatedAtDesc(Pageable page);

  /** The machine of a vendor with this vendor terminal id. */
  Optional<PaymentTerminalEntity> findByProviderAndTerminalId(String provider, String terminalId);

  /** The machine whose own money account is this one. */
  Optional<PaymentTerminalEntity> findByMoneyAccountId(UUID moneyAccountId);
}
