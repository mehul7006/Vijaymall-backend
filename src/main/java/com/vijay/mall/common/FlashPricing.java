package com.vijay.mall.common;

import com.vijay.mall.common.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * What the ordering, counter and catalog code ask of the flash sale module. Consumers take it through an optional setter
 * (null = the feature is off and everything is priced at the list price).
 */
public interface FlashPricing {
  /**
   * Where a sale happens: the customer app ({@code ONLINE}) or the shop counter ({@code POS}); a flash sale can be limited to one of them.
   */
  enum Channel { ONLINE, POS }

  /**
   * The part of a quantity that sells at the sale price: {@code saleQty} units at {@code saleUnitPricePaise}; the caller prices
   * the rest (quantity - saleQty) at the list price.
   */
  record Applied(UUID flashSaleId, UUID flashItemId, long saleUnitPricePaise, int saleQty, long listPricePaise) {}

  /** One line that used the sale: what is recorded (and counted against the limits) when the order or bill is created. */
  record Claim(UUID flashSaleId, UUID productId, int qty, long unitPricePaise, long listPricePaise) {}

  /** What the shop shows for a product on sale right now. unitsLeft is null when the quantity is not limited. */
  record Offer(UUID flashSaleId, String name, long listPricePaise, long salePricePaise, int discountPercent, Integer unitsLeft,
               int perCustomerLimit, Instant startsAt, Instant endsAt) {}

  /** The sale (or its quantity) is no longer what the quote showed: answered as the usual PRICE_CHANGED conflict. */
  class Refused extends ApiException {
    private final String reason;

    /**
     * Creates the conflict.
     * @param reason why the offer no longer fits, see {@link #reason()}
     */
    public Refused(String reason) {
      super(HttpStatus.CONFLICT, "PRICE_CHANGED", "A flash sale offer changed, please review the prices", Map.of("reason", reason));
      this.reason = reason;
    }

    /** LIMIT_REACHED, CUSTOMER_LIMIT or ENDED. */
    public String reason() { return reason; }
  }

  /** The sale price for up to {@code qty} units of the product, or empty when no sale applies (or nothing is left of it). */
  Optional<Applied> apply(UUID productId, Channel channel, long listPricePaise, UUID userIdOrNull, int qty);

  /** Counts the claims against the sale, in the caller's transaction. Throws {@link Refused} (and counts nothing) when a claim no longer fits. */
  void consume(Channel channel, UUID refId, UUID userIdOrNull, List<Claim> claims);

  /** Same for a bill that already happened (an offline till): claims that no longer fit are skipped instead of refused. */
  void consumeLenient(Channel channel, UUID refId, UUID userIdOrNull, List<Claim> claims);

  /** Gives back what an order (or bill) consumed. Safe to call twice: the units come back once. */
  void release(UUID refId);

  /** Active offers for products, keyed by product id; the map holds the list price the caller knows for each product. */
  Map<UUID, Offer> offers(Map<UUID, Long> listPricesByProduct);
}
