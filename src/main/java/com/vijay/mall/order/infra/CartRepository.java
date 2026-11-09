package com.vijay.mall.order.infra;

import com.vijay.mall.order.domain.Cart;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Plain SQL for the basket tables (no entity: the rows are only ever read and replaced whole). */
@Repository
public class CartRepository {
  private final JdbcClient jdbc;

  CartRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static OffsetDateTime utc(Instant t) { return OffsetDateTime.ofInstant(t, ZoneOffset.UTC); }

  /** The saved basket, or null when the user has never saved one. Lines of deleted, inactive or sold-out products are left out. */
  public Cart find(UUID userId) {
    var head = jdbc.sql("select coupon_code, coin_mode, coin_percent, updated_at from cart where user_id = :u")
        .param("u", userId)
        .query((rs, _) -> new Object[] {rs.getString(1), rs.getString(2), (Integer) rs.getObject(3),
            rs.getObject(4, OffsetDateTime.class).toInstant()})
        .optional();
    if (head.isEmpty()) return null;
    List<Cart.Line> lines = jdbc.sql("""
            select ci.product_id, ci.qty
            from cart_item ci
            join product p on p.id = ci.product_id and p.active
            join inventory i on i.product_id = p.id and i.qty_on_hand > 0
            where ci.user_id = :u
            order by ci.position""")
        .param("u", userId)
        .query((rs, _) -> new Cart.Line(rs.getObject(1, UUID.class), rs.getInt(2)))
        .list();
    Object[] h = head.get();
    return new Cart(lines, (String) h[0], CoinRedemption.Mode.valueOf((String) h[1]), (Integer) h[2], (Instant) h[3]);
  }

  /** Replaces the whole basket. Products that no longer exist are skipped instead of failing the request. */
  @Transactional
  public void replace(UUID userId, Cart cart, Instant now) {
    jdbc.sql("delete from cart_item where user_id = :u").param("u", userId).update();
    int position = 0;
    for (Cart.Line l : cart.items()) {
      jdbc.sql("""
              insert into cart_item (user_id, product_id, qty, position, updated_at)
              select :u, p.id, :q, :pos, :now from product p where p.id = :p""")
          .param("u", userId).param("p", l.productId()).param("q", l.qty()).param("pos", position++)
          .param("now", utc(now)).update();
    }
    jdbc.sql("""
            insert into cart (user_id, coupon_code, coin_mode, coin_percent, updated_at)
            values (:u, :c, :m, :pct, :now)
            on conflict (user_id) do update
              set coupon_code = excluded.coupon_code, coin_mode = excluded.coin_mode,
                  coin_percent = excluded.coin_percent, updated_at = excluded.updated_at""")
        .param("u", userId).param("c", cart.couponCode()).param("m", cart.coinMode().name())
        .param("pct", cart.percent()).param("now", utc(now)).update();
  }

  /** Removes the user's basket lines and header. */
  @Transactional
  public void delete(UUID userId) {
    jdbc.sql("delete from cart_item where user_id = :u").param("u", userId).update();
    jdbc.sql("delete from cart where user_id = :u").param("u", userId).update();
  }
}
