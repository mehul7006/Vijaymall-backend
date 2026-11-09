package com.vijay.mall.inventory.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The lot the next unit of a product will come from and, for a lot-priced product, when the price will change. The counter shows
 * {@code nextPriceChange} as a notice ("after 5 more the price is 120").
 *
 * @param lotId the lot the next unit comes from
 * @param qtyLeftInLot units left in that lot
 * @param batchNo the lot's batch number (may be empty)
 * @param expiryDate the lot's expiry date (may be null)
 * @param nextPriceChange null when the price stays the same for all stock on hand
 */
public record LotInfo(UUID lotId, int qtyLeftInLot, String batchNo, LocalDate expiryDate, NextPriceChange nextPriceChange) {
  /**
   * The price and MRP from which the units after {@code afterQty} units are sold.
   *
   * @param afterQty how many units still go at the current price
   * @param newPricePaise the price of the next unit after that
   * @param newMrpPaise the MRP of the next unit after that
   */
  public record NextPriceChange(int afterQty, long newPricePaise, long newMrpPaise) {}

  /**
   * Works out the lot info from a product's lots with stock, already in the order they are used.
   *
   * @param ordered the lots with stock, first to be used first
   * @param lotPriced whether the lots' own prices sell (else no price change is ever announced)
   * @param pricePaise the product's price, used for a lot that has none of its own
   * @param mrpPaise the product's MRP, used for a lot that has none of its own
   * @return empty when there is no stock
   */
  public static Optional<LotInfo> of(List<Lot> ordered, boolean lotPriced, long pricePaise, long mrpPaise) {
    if (ordered.isEmpty()) return Optional.empty();
    Lot current = ordered.getFirst();
    NextPriceChange change = null;
    if (lotPriced) {
      int same = 0;
      for (Lot l : ordered) {
        if (l.priceDiffersFrom(current)) {
          change = new NextPriceChange(same, l.pricePaise() == null ? pricePaise : l.pricePaise(), l.mrpPaise() == null ? mrpPaise : l.mrpPaise());
          break;
        }
        same += l.qtyRemaining();
      }
    }
    return Optional.of(new LotInfo(current.id(), current.qtyRemaining(), current.batchNo(), current.expiryDate(), change));
  }
}
