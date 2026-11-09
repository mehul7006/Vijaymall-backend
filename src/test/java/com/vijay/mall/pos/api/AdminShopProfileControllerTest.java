package com.vijay.mall.pos.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.app.ShopProfileService.Profile;
import com.vijay.mall.pos.app.ShopProfileService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class AdminShopProfileControllerTest {
  private final UUID admin = UUID.randomUUID();
  private final Profile profile = new Profile("/logo", "a@b.in", "", "", "", "", "", List.of(), "", true, null, null);
  private ShopProfileService service;
  private AdminShopProfileController controller;

  @BeforeEach
  void setUp() {
    service = mock(ShopProfileService.class);
    controller = new AdminShopProfileController(service);
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(admin.toString()).claim("role", "ADMIN")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null));
  }

  @AfterEach
  void clear() { SecurityContextHolder.clearContext(); }

  @Test
  void getReturnsTheProfile() {
    when(service.get()).thenReturn(profile);
    assertThat(controller.get()).isSameAs(profile);
  }

  @Test
  void saveKeepsOrderingOnWhenTheSwitchIsLeftOut() {
    when(service.save(any(), any())).thenReturn(profile);
    var body = new AdminShopProfileController.ProfileBody("a@b.in", "", "", "", "", "", List.of(), "", null, null);
    assertThat(controller.save(body)).isSameAs(profile);
    var captured = ArgumentCaptor.forClass(Profile.class);
    verify(service).save(captured.capture(), ArgumentMatchers.eq(admin));
    assertThat(captured.getValue().onlineOrdersEnabled()).isTrue();
    var off = new AdminShopProfileController.ProfileBody("a@b.in", "", "", "", "", "", List.of(), "", false, "Closed");
    controller.save(off);
    controller.save(new AdminShopProfileController.ProfileBody("a@b.in", "", "", "", "", "", List.of(), "", true, null));
    verify(service, Mockito.times(3)).save(captured.capture(), ArgumentMatchers.eq(admin));
    assertThat(captured.getAllValues()).extracting(Profile::onlineOrdersEnabled).containsExactly(true, true, false, true);
  }

  @Test
  void anEmptyLogoFileIsRefusedAndARealOneIsStored() throws Exception {
    assertThatThrownBy(() -> controller.logo(new MockMultipartFile("file", new byte[0]))).isInstanceOf(ApiException.class);
    verify(service, never()).setLogo(any(), any());
    byte[] png = {1, 2, 3};
    when(service.setLogo(png, admin)).thenReturn(profile);
    assertThat(controller.logo(new MockMultipartFile("file", png))).isSameAs(profile);
  }

  @Test
  void removeLogoDelegates() {
    when(service.removeLogo(admin)).thenReturn(profile);
    assertThat(controller.removeLogo()).isSameAs(profile);
  }
}
