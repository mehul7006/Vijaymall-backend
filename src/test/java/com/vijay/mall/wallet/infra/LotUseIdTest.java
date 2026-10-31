package com.vijay.mall.wallet.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LotUseIdTest {
  private final UUID r = UUID.randomUUID();
  private final UUID l = UUID.randomUUID();

  @Test
  void equalWhenBothPartsMatch() {
    LotUseId a = new LotUseId(r, l);
    assertThat(a).isEqualTo(new LotUseId(r, l)).hasSameHashCodeAs(new LotUseId(r, l)).isEqualTo(a);
  }

  @Test
  void differentWhenEitherPartDiffersOrTypeDiffers() {
    LotUseId a = new LotUseId(r, l);
    assertThat(a).isNotEqualTo(new LotUseId(UUID.randomUUID(), l))
        .isNotEqualTo(new LotUseId(r, UUID.randomUUID()))
        .isNotEqualTo(new LotUseId())
        .isNotEqualTo(null)
        .isNotEqualTo("x");
    assertThat(new LotUseId()).isEqualTo(new LotUseId()).hasSameHashCodeAs(new LotUseId());
  }
}
