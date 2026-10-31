package com.vijay.mall.payment.infra;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentSettingJpa extends JpaRepository<PaymentSettingEntity, String> {
  List<PaymentSettingEntity> findAllByOrderBySortOrderAsc();
}
