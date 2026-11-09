package com.vijay.mall.pos.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.pos.app.OfflineExceptionService;
import com.vijay.mall.pos.infra.OfflineExceptionRepository.Row;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class AdminOfflineExceptionControllerTest {
  private final UUID admin = UUID.randomUUID();
  private final OfflineExceptionService service = mock(OfflineExceptionService.class);
  private final AdminOfflineExceptionController controller = new AdminOfflineExceptionController(service);

  @AfterEach
  void clear() { SecurityContextHolder.clearContext(); }

  @Test
  void listAndReviewGoThroughTheService() {
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(admin.toString()).claim("role", "ADMIN")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null));
    Page<Row> page = new Page<>(List.of(), 0, 20, 0);
    when(service.list("OPEN", 0, 20)).thenReturn(page);
    assertThat(controller.list("OPEN", 0, 20)).isSameAs(page);
    UUID id = UUID.randomUUID();
    Row row = mock(Row.class);
    when(service.review(id, "ok", admin)).thenReturn(row);
    assertThat(controller.review(id, new AdminOfflineExceptionController.ReviewBody("ok"))).isSameAs(row);
    verify(service).review(id, "ok", admin);
  }
}
