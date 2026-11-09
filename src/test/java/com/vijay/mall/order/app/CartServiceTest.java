package com.vijay.mall.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.order.domain.Cart;
import com.vijay.mall.order.infra.CartRepository;
import com.vijay.mall.wallet.domain.CoinRedemption.Mode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class CartServiceTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
  private final CartRepository repo = mock(CartRepository.class);
  private final CartService service = new CartService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID user = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();

  @Test
  void noSavedBasketReadsAsEmptyNotMissing() {
    when(repo.find(user)).thenReturn(null);
    assertThat(service.get(user)).isEqualTo(Cart.empty());
  }

  @Test
  void savedBasketIsReturnedAsStored() {
    var stored = new Cart(List.of(new Cart.Line(product, 2)), "FEST", Mode.ALL, null, NOW);
    when(repo.find(user)).thenReturn(stored);
    assertThat(service.get(user)).isSameAs(stored);
  }

  @Test
  void replaceStoresTheCleanedBasketAtTheClockTimeAndReturnsWhatIsStored() {
    var stored = new Cart(List.of(new Cart.Line(product, 3)), "FEST", Mode.NONE, null, NOW);
    when(repo.find(user)).thenReturn(stored);
    var result = service.replace(user, List.of(new Cart.Line(product, 1), new Cart.Line(product, 2)), " fest ", null, null);
    var saved = ArgumentCaptor.forClass(Cart.class);
    verify(repo).replace(ArgumentMatchers.eq(user), saved.capture(), ArgumentMatchers.eq(NOW));
    assertThat(saved.getValue().items()).containsExactly(new Cart.Line(product, 3));
    assertThat(saved.getValue().couponCode()).isEqualTo("FEST");
    assertThat(result).isSameAs(stored);
  }

  @Test
  void clearDeletesTheBasket() {
    service.clear(user);
    verify(repo).delete(user);
  }
}
