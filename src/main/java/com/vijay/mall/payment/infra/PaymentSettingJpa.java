package com.vijay.mall.payment.infra;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code payment_setting}, in display order. */
interface PaymentSettingJpa extends JpaRepository<PaymentSettingEntity, String> {
  List<PaymentSettingEntity> findAllByOrderBySortOrderAsc();
}
