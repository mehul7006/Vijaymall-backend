package com.vijay.mall.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** The admin config screen's endpoints, with the settings and the connection testers mocked. */
class AdminConfigControllerTest {
  private final UUID admin = UUID.randomUUID();
  private RuntimeSettings settings;
  private BootstrapStatus bootstrap;
  private ConnectionTester razorpay;
  private AdminConfigController controller;

  @BeforeEach
  void setUp() {
    settings = mock(RuntimeSettings.class);
    bootstrap = mock(BootstrapStatus.class);
    razorpay = mock(ConnectionTester.class);
    when(razorpay.target()).thenReturn("razorpay");
    var other = mock(ConnectionTester.class);
    when(other.target()).thenReturn("fcm");
    controller = new AdminConfigController(settings, bootstrap, List.of(other, razorpay));
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(admin.toString()).claim("role", "ADMIN")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null));
  }

  @AfterEach
  void clear() { SecurityContextHolder.clearContext(); }

  @Test
  void testWithoutABodyPassesNoArguments() {
    when(razorpay.test(Map.of())).thenReturn(new ConnectionTester.Result(true, "ok"));
    assertThat(controller.test("razorpay", null).ok()).isTrue();
  }

  @Test
  void testWithABodyButNoArgumentsPassesNoArguments() {
    when(razorpay.test(Map.of())).thenReturn(new ConnectionTester.Result(false, "refused"));
    assertThat(controller.test("razorpay", new AdminConfigController.TestBody(null)).message()).isEqualTo("refused");
  }

  @Test
  void testPassesTheScreensArguments() {
    when(razorpay.test(Map.of("mobile", "9000000001"))).thenReturn(new ConnectionTester.Result(true, "sent"));
    var r = controller.test("razorpay", new AdminConfigController.TestBody(Map.of("mobile", "9000000001")));
    assertThat(r.message()).isEqualTo("sent");
    verify(razorpay).test(Map.of("mobile", "9000000001"));
  }

  @Test
  void anUnknownTargetIsNotFound() {
    assertThatThrownBy(() -> controller.test("nope", null)).isInstanceOf(ApiException.class);
  }

  @Test
  void viewGroupsTheRowsAndSaveReturnsTheFreshView() {
    var row = new RuntimeSettings.Row("k", "PAYMENT", "Online payment", "STRING", "Label", "", false, true, "v", "DATABASE", List.of(), null, null, false);
    when(settings.view()).thenReturn(List.of(row, row));
    when(settings.issues()).thenReturn(List.of("careful"));
    var changes = List.of(new RuntimeSettings.Change("k", "v"));
    var view = controller.save(new AdminConfigController.SaveBody(changes));
    verify(settings).save(changes, admin);
    assertThat(view.groups()).hasSize(1);
    assertThat(view.groups().get(0).items()).hasSize(2);
    assertThat(view.issues()).containsExactly("careful");
    assertThat(controller.get().groups()).hasSize(1);
    when(bootstrap.items()).thenReturn(List.of());
    assertThat(controller.bootstrap()).isEmpty();
  }
}
