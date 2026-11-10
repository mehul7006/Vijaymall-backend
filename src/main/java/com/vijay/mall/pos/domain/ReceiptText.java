package com.vijay.mall.pos.domain;

import com.vijay.mall.common.tax.GstRates;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Plain-text thermal receipt (32 columns for 58 mm, 48 for 80 mm). */
public final class ReceiptText {
    private ReceiptText() { }

    /**
     * Lays a bill out as plain text for a thermal printer.
     *
     * @param width characters per line; only 32 (58 mm paper) and 48 (80 mm paper) are accepted
     * @return the receipt, one line per row, ending with a newline
     * @throws IllegalArgumentException if the width is not 32 or 48
     */
    public static String render(ReceiptData r, int width) {
        checkWidth(width);
        List<String> out = new ArrayList<>();
        header(out, r.shopName(), r.address(), r.phone(), r.gstin(), width);
        out.add("TAX INVOICE");
        out.add(split("Inv: " + r.invoiceNo(), "", width));
        out.add(r.dateTime());
        out.add("Cashier: " + r.cashier() + (r.customer().isBlank() ? "" : "  Cust: " + r.customer()));
        out.add("-".repeat(width));
        r.lines().forEach(l -> {
            wrap(l.name(), width).forEach(out::add);
            String qty = l.soldBy() == SoldBy.WEIGHT
                    ? "%d.%03d kg".formatted(l.qty() / 1000, l.qty() % 1000)
                    : "%d x %s".formatted(l.qty(), Money.plain(l.unitPricePaise()));
            out.add(split("  " + qty + " (" + GstRates.plain(l.gstPercent()) + "%)", Money.plain(l.netPaise()), width));
        });
        out.add("-".repeat(width));
        out.add(split("Subtotal", Money.plain(r.subtotalPaise()), width));
        if (r.couponDiscountPaise() > 0) out.add(split("Coupon", "-" + Money.plain(r.couponDiscountPaise()), width));
        if (r.coinDiscountPaise() > 0) out.add(split("Coins", "-" + Money.plain(r.coinDiscountPaise()), width));
        if (r.manualDiscountPaise() > 0) out.add(split("Discount", "-" + Money.plain(r.manualDiscountPaise()), width));
        if (r.roundOffPaise() != 0) {
            out.add(split("Round off", (r.roundOffPaise() > 0 ? "+" : "-") + Money.plain(Math.abs(r.roundOffPaise())), width));
        }
        out.add(split("TOTAL", Money.plain(r.totalPaise()), width));
        out.add("-".repeat(width));
        out.add(split("Taxable", Money.plain(r.taxablePaise()), width));
        out.add(split("CGST", Money.plain(r.cgstPaise()), width));
        out.add(split("SGST", Money.plain(r.sgstPaise()), width));
        out.add("-".repeat(width));
        r.payments().forEach(p -> out.add(split(p.mode(), Money.plain(p.amountPaise()), width)));
        if (r.changePaise() > 0) out.add(split("Change", Money.plain(r.changePaise()), width));
        if (r.coinsEarned() > 0) out.add("Coins earned: " + r.coinsEarned());
        out.add("-".repeat(width));
        wrap(r.footer(), width).forEach(l -> out.add(center(l, width)));
        return out.stream().collect(Collectors.joining("\n", "", "\n"));
    }

    /**
     * Lays a credit note (the receipt of a return) out as plain text: shop header, credit note and original invoice numbers,
     * the returned lines with quantity and refund, the GST split, the refund payments and the refund total.
     *
     * @param width characters per line; only 32 and 48 are accepted
     * @throws IllegalArgumentException if the width is not 32 or 48
     */
    public static String renderCreditNote(CreditNoteData r, int width) {
        checkWidth(width);
        List<String> out = new ArrayList<>();
        header(out, r.shopName(), r.address(), r.phone(), r.gstin(), width);
        out.add("CREDIT NOTE");
        out.add(split("CN: " + r.creditNoteNo(), "", width));
        out.add(split("Orig Inv: " + r.invoiceNo(), "", width));
        out.add(r.dateTime());
        out.add("Cashier: " + r.cashier() + (r.customer().isBlank() ? "" : "  Cust: " + r.customer()));
        out.add("-".repeat(width));
        r.lines().forEach(l -> {
            wrap(l.name(), width).forEach(out::add);
            String qty = l.soldBy() == SoldBy.WEIGHT ? "%d.%03d kg".formatted(l.qty() / 1000, l.qty() % 1000) : "%d".formatted(l.qty());
            out.add(split("  Returned " + qty + " (" + GstRates.plain(l.gstPercent()) + "%)", Money.plain(l.refundPaise()), width));
        });
        out.add("-".repeat(width));
        out.add(split("Taxable", Money.plain(r.taxablePaise()), width));
        out.add(split("CGST", Money.plain(r.cgstPaise()), width));
        out.add(split("SGST", Money.plain(r.sgstPaise()), width));
        out.add("-".repeat(width));
        if (r.couponSharePaise() > 0) out.add(split("Coupon share", "-" + Money.plain(r.couponSharePaise()), width));
        if (r.couponClawbackPaise() > 0) out.add(split("Coupon taken back", "-" + Money.plain(r.couponClawbackPaise()), width));
        out.add(split("Refund total", Money.plain(r.refundTotalPaise()), width));
        if (r.creditAdjustedPaise() > 0) out.add(split("Credit reduced", Money.plain(r.creditAdjustedPaise()), width));
        r.refunds().forEach(p -> out.add(split("Refund " + p.mode(), Money.plain(p.amountPaise()), width)));
        if (!r.reason().isBlank()) wrap("Reason: " + r.reason(), width).forEach(out::add);
        out.add("-".repeat(width));
        wrap(r.footer(), width).forEach(l -> out.add(center(l, width)));
        return out.stream().collect(Collectors.joining("\n", "", "\n"));
    }

    /**
     * Lays the receipt of a credit repayment out as plain text: shop header, receipt number, customer, amount, the payment
     * modes and the credit balance before and after.
     *
     * @param width characters per line; only 32 and 48 are accepted
     * @throws IllegalArgumentException if the width is not 32 or 48
     */
    public static String renderRepayment(RepaymentData r, int width) {
        checkWidth(width);
        List<String> out = new ArrayList<>();
        header(out, r.shopName(), r.address(), r.phone(), r.gstin(), width);
        out.add("CREDIT PAYMENT RECEIPT");
        out.add(split("Receipt: " + r.receiptNo(), "", width));
        out.add(r.dateTime());
        out.add("Cashier: " + r.cashier());
        out.add("Customer: " + r.customer());
        out.add("-".repeat(width));
        out.add(split("Balance before", Money.plain(r.balanceBeforePaise()), width));
        r.payments().forEach(p -> out.add(split("Paid " + p.mode(), Money.plain(p.amountPaise()), width)));
        out.add(split("Amount paid", Money.plain(r.amountPaise()), width));
        out.add(split("Balance after", Money.plain(r.balanceAfterPaise()), width));
        if (!r.note().isBlank()) wrap("Note: " + r.note(), width).forEach(out::add);
        out.add("-".repeat(width));
        wrap(r.footer(), width).forEach(l -> out.add(center(l, width)));
        return out.stream().collect(Collectors.joining("\n", "", "\n"));
    }

    private static void checkWidth(int width) {
        if (width != 32 && width != 48) {
            throw new IllegalArgumentException("width must be 32 or 48");
        }
    }

    /** The shop name, address, phone and GSTIN, centred, followed by a rule. */
    private static void header(List<String> out, String shopName, String address, String phone, String gstin, int width) {
        out.add(center(shopName, width));
        wrap(address, width).forEach(l -> out.add(center(l, width)));
        if (!phone.isBlank()) out.add(center("Ph: " + phone, width));
        if (!gstin.isBlank()) out.add(center("GSTIN: " + gstin, width));
        out.add("-".repeat(width));
    }

    /** Centres a line by padding the left; a line as wide as the paper is returned unchanged. */
    static String center(String s, int w) {
        return s.length() >= w ? s : " ".repeat((w - s.length()) / 2) + s;
    }

    /** Left text and right text on one line; the left is cut if they do not fit. */
    static String split(String left, String right, int w) {
        int room = w - right.length() - 1;
        String l = left.length() > room ? left.substring(0, Math.max(room, 0)) : left;
        return l + " ".repeat(Math.max(1, w - l.length() - right.length())) + right;
    }

    /** Breaks text into lines of at most {@code w} characters at spaces; a word longer than a line is cut. */
    static List<String> wrap(String text, int w) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            if (word.isEmpty()) continue;
            String w2 = word.length() > w ? word.substring(0, w) : word;
            if (cur.length() > 0 && cur.length() + 1 + w2.length() > w) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w2);
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }
}
