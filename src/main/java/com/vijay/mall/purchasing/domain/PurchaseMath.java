package com.vijay.mall.purchasing.domain;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.domain.GstCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The money maths of a purchase document, in exact paise.
 *
 * <p>Order of work: the line discount comes off each line; the document discount is spread over the lines in proportion to what is
 * left (largest remainder, so the shares add up exactly); GST and cess are charged on the taxable value that remains; freight and its GST
 * are added; the optional round-off brings the total to a whole rupee. The landed cost of a unit is the line's taxable value plus the tax
 * that is not claimed back (everything under COMPOSITION, nothing under REGULAR or NONE) plus the line's share of the freight, divided by
 * paid plus free units, kept with 4 decimals of a paisa.
 */
public final class PurchaseMath {
  private PurchaseMath() { }

  /** One line as typed: quantities, invoice price, the discount (percent wins when above zero) and the tax rates. */
  public record LineIn(int qty, int freeQty, long unitCostPaise, BigDecimal discountPercent, long discountPaise, int gstPercent,
                       BigDecimal cessPercent) {}

  /** The document-level inputs. */
  public record DocIn(GstMode mode, boolean interState, BigDecimal docDiscountPercent, long docDiscountPaise, long freightPaise,
                      int freightGstPercent, boolean roundToRupee) {}

  /** One computed line. {@code taxablePaise} is after both discounts. */
  public record LineOut(long grossPaise, long lineDiscountPaise, long docDiscountSharePaise, long taxablePaise, long cgstPaise, long sgstPaise,
                        long igstPaise, long cessPaise, long lineTotalPaise, long landedTotalPaise, BigDecimal landedUnitCost) {}

  /** The computed document; tax figures include the tax on freight. */
  public record DocOut(List<LineOut> lines, long lineDiscountPaise, long docDiscountPaise, long taxablePaise, long cgstPaise, long sgstPaise,
                       long igstPaise, long cessPaise, long freightPaise, long freightGstPaise, long roundOffPaise, long totalPaise) {}

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** {@code amount * percent / 100} to the nearest paisa, half up. */
  public static long percentOf(long amount, BigDecimal percent) {
    return BigDecimal.valueOf(amount).multiply(percent).divide(HUNDRED, 0, RoundingMode.HALF_UP).longValueExact();
  }

  /** The largest discount in paise a percent-or-amount discount can be: the percent when above zero, else the amount. */
  private static long discount(long base, BigDecimal percent, long amount) {
    return percent != null && percent.signum() > 0 ? percentOf(base, percent) : amount;
  }

  /**
   * Works out a purchase.
   *
   * @param lines the lines, at least one
   * @throws ApiException 400 when a discount is more than what it is taken from
   * @param doc the document inputs
   * @return every line and the document totals
   */
  public static DocOut compute(List<LineIn> lines, DocIn doc) {
    int n = lines.size();
    long[] gross = new long[n];
    long[] lineDisc = new long[n];
    long[] afterLine = new long[n];
    for (int i = 0; i < n; i++) {
      LineIn l = lines.get(i);
      gross[i] = Math.multiplyExact((long) l.qty(), l.unitCostPaise());
      lineDisc[i] = discount(gross[i], l.discountPercent(), l.discountPaise());
      if (lineDisc[i] > gross[i]) throw ApiException.validation("discountPaise", "the discount cannot be more than the line amount");
      afterLine[i] = gross[i] - lineDisc[i];
    }
    long afterLines = sum(afterLine);
    long docDisc = discount(afterLines, doc.docDiscountPercent(), doc.docDiscountPaise());
    if (docDisc > afterLines) throw ApiException.validation("docDiscountPaise", "the discount cannot be more than the goods value");
    long[] share = GstCalculator.allocate(docDisc, afterLine);

    long[] taxable = new long[n];
    long[] cgst = new long[n];
    long[] sgst = new long[n];
    long[] igst = new long[n];
    long[] cess = new long[n];
    long[] total = new long[n];
    long[] nonCreditable = new long[n];
    for (int i = 0; i < n; i++) {
      LineIn l = lines.get(i);
      taxable[i] = afterLine[i] - share[i];
      int rate = doc.mode() == GstMode.NONE ? 0 : l.gstPercent();
      long gst = percentOf(taxable[i], BigDecimal.valueOf(rate));
      long[] split = split(gst, doc.interState());
      cgst[i] = split[0];
      sgst[i] = split[1];
      igst[i] = split[2];
      cess[i] = doc.mode() == GstMode.NONE || l.cessPercent() == null ? 0 : percentOf(taxable[i], l.cessPercent());
      total[i] = taxable[i] + gst + cess[i];
      nonCreditable[i] = doc.mode().creditable() ? 0 : gst + cess[i];
    }

    long freightGst = doc.mode() == GstMode.NONE ? 0 : percentOf(doc.freightPaise(), BigDecimal.valueOf(doc.freightGstPercent()));
    long[] freightSplit = split(freightGst, doc.interState());
    long freightCost = doc.freightPaise() + (doc.mode().creditable() ? 0 : freightGst);
    long[] weights = sum(taxable) > 0 ? taxable : lines.stream().mapToLong(l -> l.qty() + l.freeQty()).toArray();
    long[] freightShare = GstCalculator.allocate(freightCost, weights);

    List<LineOut> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      LineIn l = lines.get(i);
      long landed = taxable[i] + nonCreditable[i] + freightShare[i];
      BigDecimal unit = BigDecimal.valueOf(landed).divide(BigDecimal.valueOf((long) l.qty() + l.freeQty()), 4, RoundingMode.HALF_UP);
      out.add(new LineOut(gross[i], lineDisc[i], share[i], taxable[i], cgst[i], sgst[i], igst[i], cess[i], total[i], landed, unit));
    }
    long subtotal = sum(total) + doc.freightPaise() + freightGst;
    long roundOff = doc.roundToRupee() ? Math.floorDiv(subtotal + 50, 100L) * 100 - subtotal : 0;
    return new DocOut(out, sum(lineDisc), docDisc, sum(taxable), sum(cgst) + freightSplit[0], sum(sgst) + freightSplit[1],
        sum(igst) + freightSplit[2], sum(cess), doc.freightPaise(), freightGst, roundOff, subtotal + roundOff);
  }

  /** Splits GST into {cgst, sgst, igst}: inter-state is all IGST, otherwise halves with the odd paisa to SGST. */
  public static long[] split(long gst, boolean interState) {
    if (interState) return new long[] {0, 0, gst};
    long half = gst / 2;
    return new long[] {half, gst - half, 0};
  }

  private static long sum(long[] values) {
    long s = 0;
    for (long v : values) s = Math.addExact(s, v);
    return s;
  }
}
