package com.vijay.mall.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.ReferenceNumbers;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.error.GlobalExceptionHandler;
import com.vijay.mall.order.api.AdminOrderController;
import com.vijay.mall.order.api.CartController;
import com.vijay.mall.order.api.OrderController;
import com.vijay.mall.order.app.OrderService;
import com.vijay.mall.order.app.QuoteService;
import com.vijay.mall.order.domain.OrderStatus;
import com.vijay.mall.order.domain.OrderViews;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.payment.api.PaymentController;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.infra.PaymentRepository;
import com.vijay.mall.support.TestAuth;
import com.vijay.mall.wallet.api.WalletController;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinMode;
import com.vijay.mall.wallet.domain.CoinRule;
import com.vijay.mall.wallet.domain.CoinSettings;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Component tests: real controllers, real JSON and validation, real error handler, mocked services.
 * No Spring context and no database, so they are fast.
 */
class ControllerComponentTest {
  private final UUID user = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();
  private QuoteService quotes;
  private OrderService orders;
  private PaymentService payments;
  private WalletService wallet;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    quotes = mock(QuoteService.class);
    orders = mock(OrderService.class);
    payments = mock(PaymentService.class);
    wallet = mock(WalletService.class);
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mvc = MockMvcBuilders.standaloneSetup(new CartController(quotes), new OrderController(orders),
            new AdminOrderController(orders), new PaymentController(payments),
            new WalletController(wallet, new ReferenceNumbers(List.of()), Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC)))
        .setControllerAdvice(new GlobalExceptionHandler()).setValidator(validator).build();
    TestAuth.login(user, "CUSTOMER");
  }

  @AfterEach
  void tearDown() { TestAuth.clear(); }

  private String items() { return "[{\"productId\":\"" + product + "\",\"qty\":2}]"; }

  private static Quote quote() {
    return new Quote(List.of(new Quote.Line(UUID.randomUUID(), "Rice", 2, 5000, 10000, true, true)), 10000, null, 0, 0, 0, 4000,
        14000, 3, List.of());
  }

  // ---------- cart ----------
  @Test
  void quoteReturnsTheContractShape() throws Exception {
    when(quotes.quote(eq(user), any())).thenReturn(quote());
    mvc.perform(post("/api/v1/cart/quote").contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":" + items() + ",\"coinMode\":\"PERCENT\",\"percent\":50,\"fulfilment\":\"DELIVERY\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalPaise").value(14000))
        .andExpect(jsonPath("$.deliveryFeePaise").value(4000))
        .andExpect(jsonPath("$.coinsToEarn").value(3))
        .andExpect(jsonPath("$.lines[0].name").value("Rice"));
    ArgumentCaptor<QuoteRequest> req = ArgumentCaptor.forClass(QuoteRequest.class);
    verify(quotes).quote(eq(user), req.capture());
    assertEquals(50, req.getValue().pct());
    assertEquals("PERCENT", req.getValue().mode().name());
  }

  @Test
  void emptyCartIsAValidationError() throws Exception {
    mvc.perform(post("/api/v1/cart/quote").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details.fields.items").exists());
  }

  @Test
  void unreadableJsonIsABadRequestNotAServerError() throws Exception {
    mvc.perform(post("/api/v1/cart/quote").contentType(MediaType.APPLICATION_JSON).content("{nope"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void couponErrorsKeepTheirReason() throws Exception {
    when(quotes.quote(any(), any())).thenThrow(ApiException.unprocessable("COUPON_INVALID", "no", Map.of("reason", "MIN_ORDER")));
    mvc.perform(post("/api/v1/cart/quote").contentType(MediaType.APPLICATION_JSON).content("{\"items\":" + items() + "}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("COUPON_INVALID"))
        .andExpect(jsonPath("$.details.reason").value("MIN_ORDER"));
  }

  // ---------- orders ----------
  private String orderBody(long expected) {
    return "{\"items\":" + items() + ",\"coinMode\":\"NONE\",\"fulfilment\":\"PICKUP\",\"paymentMethod\":\"COD\",\"expectedTotalPaise\":" + expected + "}";
  }

  private OrderViews.View view() {
    return new OrderViews.View(UUID.randomUUID(), "VSM-1001", "PLACED", "PICKUP", "COD", "NONE", List.of(), 10000, null, 0, 0, 0, 0,
        10000, 5, null, null, List.of(), Instant.parse("2026-11-01T10:00:00Z"));
  }

  @Test
  void placingAnOrderNeedsAnIdempotencyKeyAndReturns201() throws Exception {
    mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(orderBody(10000)))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    when(orders.place(eq(user), eq("abc-123"), any())).thenReturn(view());
    mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "abc-123").contentType(MediaType.APPLICATION_JSON).content(orderBody(10000)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderNo").value("VSM-1001"))
        .andExpect(jsonPath("$.placedAt").value("2026-11-01T10:00:00Z"));
    ArgumentCaptor<OrderService.PlaceCommand> cmd = ArgumentCaptor.forClass(OrderService.PlaceCommand.class);
    verify(orders).place(eq(user), eq("abc-123"), cmd.capture());
    assertEquals(10000, cmd.getValue().expectedTotalPaise());
    assertEquals("COD", cmd.getValue().paymentMethod());
  }

  @Test
  void priceChangedCarriesTheNewQuote() throws Exception {
    when(orders.place(any(), any(), any())).thenThrow(ApiException.conflict("PRICE_CHANGED", "Prices changed", Map.of("quote", quote())));
    mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content(orderBody(1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
        .andExpect(jsonPath("$.details.quote.totalPaise").value(14000));
  }

  @Test
  void outOfStockListsWhatIsLeft() throws Exception {
    when(orders.place(any(), any(), any())).thenThrow(ApiException.conflict("OUT_OF_STOCK", "x",
        Map.of("items", List.of(Map.of("productId", product.toString(), "available", 1)))));
    mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content(orderBody(1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.details.items[0].available").value(1));
  }

  @Test
  void orderListUsesPagingDefaults() throws Exception {
    when(orders.list(user, 0, 20)).thenReturn(new Page<>(List.of(), 0, 20, 0));
    mvc.perform(get("/api/v1/orders")).andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void adminStatusChangeRejectsUnknownStatusAndPassesTheRest() throws Exception {
    UUID id = UUID.randomUUID();
    mvc.perform(post("/api/v1/admin/orders/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"TELEPORTED\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/admin/orders/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    when(orders.adminChangeStatus(eq(id), eq(OrderStatus.CONFIRMED), eq("ok"), eq(user)))
        .thenThrow(ApiException.conflict("ILLEGAL_TRANSITION", "no", Map.of()));
    mvc.perform(post("/api/v1/admin/orders/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONFIRMED\",\"note\":\"ok\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
  }

  // ---------- payments ----------
  @Test
  void webhookGetsTheRawBodyAndLowercasedHeaders() throws Exception {
    String raw = "{\"event\":\"payment.captured\"}";
    mvc.perform(post("/api/v1/payments/webhook/razorpay").contentType(MediaType.APPLICATION_JSON)
            .header("X-Razorpay-Signature", "sig123").header("X-Razorpay-Event-Id", "evt_1").content(raw))
        .andExpect(status().isOk());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
    verify(payments).handleWebhook(eq("razorpay"), eq(raw), headers.capture());
    assertEquals("sig123", headers.getValue().get("x-razorpay-signature"));
    assertEquals("evt_1", headers.getValue().get("x-razorpay-event-id"));
  }

  @Test
  void badSignatureIs400() throws Exception {
    Mockito.doThrow(new ApiException(HttpStatus.BAD_REQUEST, "BAD_SIGNATURE", "Invalid signature"))
        .when(payments).handleWebhook(any(), any(), any());
    mvc.perform(post("/api/v1/payments/webhook/razorpay").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_SIGNATURE"));
  }

  @Test
  void paymentMethodsListsOnlyWhatTheServiceReturns() throws Exception {
    when(payments.enabledMethods()).thenReturn(List.of(new PaymentRepository.Setting("COD", true)));
    mvc.perform(get("/api/v1/payment-methods")).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("COD")).andExpect(jsonPath("$[1]").doesNotExist());
  }

  // ---------- wallet ----------
  @Test
  void walletShowsCoinsValueAndActiveRules() throws Exception {
    when(wallet.settings()).thenReturn(new CoinSettings(100, 50, 12));
    when(wallet.balance(user)).thenReturn(120);
    when(wallet.activeRules(any())).thenReturn(List.of(new CoinRule("Spend 1000 get 10", 100000, 10, CoinMode.PRO_RATA, 0)));
    mvc.perform(get("/api/v1/wallet")).andExpect(status().isOk())
        .andExpect(jsonPath("$.coins").value(120))
        .andExpect(jsonPath("$.valuePaise").value(12000))
        .andExpect(jsonPath("$.maxRedeemPercent").value(50))
        .andExpect(jsonPath("$.rules[0].mode").value("PRO_RATA"));
  }
}
