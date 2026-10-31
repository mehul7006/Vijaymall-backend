package com.vijay.mall.pos.infra;

import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.WeighedMode;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ShopSettingRepository {
  private final PosShopJpa jpa;

  ShopSettingRepository(PosShopJpa jpa) { this.jpa = jpa; }

  public ShopSetting get() {
    ShopSettingEntity e = jpa.findById(1).orElseThrow();
    return new ShopSetting(e.shopName, e.address, e.phone, e.gstin, e.stateCode, e.upiId, e.invoicePrefix,
        e.receiptFooter, e.weighedPrefix, WeighedMode.valueOf(e.weighedMode));
  }

  @Transactional
  public void save(ShopSetting s, Instant now) {
    ShopSettingEntity e = jpa.findById(1).orElseThrow();
    e.shopName = s.shopName();
    e.address = s.address();
    e.phone = s.phone();
    e.gstin = s.gstin();
    e.stateCode = s.stateCode();
    e.upiId = s.upiId();
    e.invoicePrefix = s.invoicePrefix();
    e.receiptFooter = s.receiptFooter();
    e.weighedPrefix = s.weighedPrefix();
    e.weighedMode = s.weighedMode().name();
    e.updatedAt = now;
  }
}
