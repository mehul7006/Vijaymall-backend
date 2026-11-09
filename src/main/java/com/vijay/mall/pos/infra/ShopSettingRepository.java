package com.vijay.mall.pos.infra;

import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.WeighedMode;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes the single row of shop settings. */
@Repository
public class ShopSettingRepository {
  private final PosShopJpa jpa;

  ShopSettingRepository(PosShopJpa jpa) { this.jpa = jpa; }

  private static String nz(String s) { return s == null ? "" : s; }

  /** The shop settings. */
  public ShopSetting get() {
    ShopSettingEntity e = jpa.findById(1).orElseThrow();
    return new ShopSetting(e.shopName, e.address, e.phone, e.gstin, e.stateCode, e.upiId, e.invoicePrefix,
        e.receiptFooter, e.weighedPrefix, WeighedMode.valueOf(e.weighedMode), e.promise == null ? "" : e.promise,
        e.details == null ? "" : e.details, nz(e.shortName), nz(e.shopNameGu), nz(e.addressGu), nz(e.promiseGu), nz(e.detailsGu));
  }

  /** Overwrites the shop settings. */
  @Transactional
  public void save(ShopSetting s, Instant now) {
    ShopSettingEntity e = jpa.findById(1).orElseThrow();
    e.shopName = s.shopName();
    e.address = s.address();
    e.phone = s.phone();
    e.promise = s.promise() == null ? "" : s.promise();
    e.details = s.details() == null ? "" : s.details();
    e.shortName = nz(s.shortName());
    e.shopNameGu = nz(s.shopNameGu());
    e.addressGu = nz(s.addressGu());
    e.promiseGu = nz(s.promiseGu());
    e.detailsGu = nz(s.detailsGu());
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
