package com.vijay.mall.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vijay.mall.common.error.GlobalExceptionHandler;
import com.vijay.mall.order.api.CartBasketController;
import com.vijay.mall.order.app.CartService;
import com.vijay.mall.order.domain.Cart;
import com.vijay.mall.support.TestAuth;
import com.vijay.mall.wallet.domain.CoinRedemption.Mode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** The saved-basket endpoints: JSON shape and input validation, with a mocked service. */
class CartBasketControllerTest {
  private final UUID user = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();
  private CartService carts;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    carts = mock(CartService.class);
    var validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mvc = MockMvcBuilders.standaloneSetup(new CartBasketController(carts))
        .setControllerAdvice(new GlobalExceptionHandler()).setValidator(validator).build();
    TestAuth.login(user, "CUSTOMER");
  }

  @AfterEach
  void tearDown() { TestAuth.clear(); }

  @Test
  void getReturnsTheContractShape() throws Exception {
    when(carts.get(user)).thenReturn(new Cart(List.of(new Cart.Line(product, 2)), "FEST", Mode.PERCENT, 50,
        Instant.parse("2027-03-01T10:00:00Z")));
    mvc.perform(get("/api/v1/cart"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].productId").value(product.toString()))
        .andExpect(jsonPath("$.items[0].qty").value(2))
        .andExpect(jsonPath("$.couponCode").value("FEST"))
        .andExpect(jsonPath("$.coinMode").value("PERCENT"))
        .andExpect(jsonPath("$.percent").value(50))
        .andExpect(jsonPath("$.updatedAt").exists());
  }

  @Test
  void putPassesTheBodyThroughAndReturnsTheStoredBasket() throws Exception {
    var stored = new Cart(List.of(new Cart.Line(product, 2)), null, Mode.NONE, null, Instant.parse("2027-03-01T10:00:00Z"));
    when(carts.replace(eq(user), any(), eq(null), eq(null), eq(null))).thenReturn(stored);
    mvc.perform(put("/api/v1/cart").contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[{\"productId\":\"" + product + "\",\"qty\":2}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].qty").value(2));
    verify(carts).replace(eq(user), eq(List.of(new Cart.Line(product, 2))), eq(null), eq(null), eq(null));
  }

  @Test
  void putAnEmptyBasketIsAllowed() throws Exception {
    when(carts.replace(eq(user), any(), eq(null), eq(null), eq(null))).thenReturn(Cart.empty());
    mvc.perform(put("/api/v1/cart").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void putRejectsBadInput() throws Exception {
    String id = "\"productId\":\"" + product + "\"";
    mvc.perform(put("/api/v1/cart").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(put("/api/v1/cart").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{" + id + ",\"qty\":0}]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(put("/api/v1/cart").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{" + id + ",\"qty\":1000}]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(put("/api/v1/cart").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"qty\":1}]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(put("/api/v1/cart").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[],\"percent\":101}"))
        .andExpect(status().isBadRequest());
    mvc.perform(put("/api/v1/cart").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[],\"coinMode\":\"BOGUS\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void tooManyLinesAreRejected() throws Exception {
    var lines = new StringBuilder();
    for (int i = 0; i <= Cart.MAX_LINES; i++) {
      if (i > 0) lines.append(',');
      lines.append("{\"productId\":\"").append(UUID.randomUUID()).append("\",\"qty\":1}");
    }
    mvc.perform(put("/api/v1/cart").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[" + lines + "]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteClearsTheBasket() throws Exception {
    mvc.perform(delete("/api/v1/cart")).andExpect(status().isNoContent());
    verify(carts).clear(user);
  }
}
