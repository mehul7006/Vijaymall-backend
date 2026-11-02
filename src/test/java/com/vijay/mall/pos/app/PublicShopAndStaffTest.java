package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.pos.api.PublicShopController;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.WeighedMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicShopAndStaffTest {
  @Test
  void publicShopShowsOnlyCustomerFacingDetails() throws Exception {
    var shop = mock(ShopService.class);
    when(shop.get()).thenReturn(new ShopSetting("Vijay Super Mall", "Station Road", "99999 00000", "24ABCDE1234F1Z5", "24",
        "vijay@upi", "VSM", "Thanks", "2", WeighedMode.WEIGHT_GRAMS));
    MockMvcBuilders.standaloneSetup(new PublicShopController(shop)).build().perform(get("/api/v1/shop-settings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shopName").value("Vijay Super Mall"))
        .andExpect(jsonPath("$.phone").value("99999 00000"))
        .andExpect(jsonPath("$.address").value("Station Road"))
        .andExpect(jsonPath("$.gstin").doesNotExist())
        .andExpect(jsonPath("$.upiId").doesNotExist())
        .andExpect(jsonPath("$.invoicePrefix").doesNotExist());
  }

  @Test
  void cashierIsActiveUnlessBlocked() {
    var users = mock(UserRepository.class);
    var service = new StaffService(users, mock(AuditService.class), Clock.fixed(Instant.parse("2027-03-01T10:00:00Z"), ZoneOffset.UTC));
    UUID blocked = UUID.randomUUID();
    UUID fine = UUID.randomUUID();
    when(users.isBlocked(blocked)).thenReturn(true);
    assertThat(service.isActive(blocked)).isFalse();
    assertThat(service.isActive(fine)).isTrue();
  }
}
