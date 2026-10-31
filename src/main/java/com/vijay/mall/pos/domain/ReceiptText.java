package com.vijay.mall.pos.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Plain-text thermal receipt (32 columns for 58 mm, 48 for 80 mm). */
public final class ReceiptText {
    private ReceiptText() { }

    public static String render(ReceiptData r, int width) {
        if (width != 32 && width != 48) {
            throw new IllegalArgumentException("width must be 32 or 48");
        }
        List<String> out = new ArrayList<>();
        out.add(center(r.shopName(), width));
        wrap(r.address(), width).forEach(l -> out.add(center(l, width)));
        if (!r.phone().isBlank()) out.add(center("Ph: " + r.phone(), width));
        if (!r.gstin().isBlank()) out.add(center("GSTIN: " + r.gstin(), width));
        out.add("-".repeat(width));
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
            out.add(split("  " + qty + " (" + l.gstPercent() + "%)", Money.plain(l.netPaise()), width));
        });
        out.add("-".repeat(width));
        out.add(split("Subtotal", Money.plain(r.subtotalPaise()), width));
        if (r.couponDiscountPaise() > 0) out.add(split("Coupon", "-" + Money.plain(r.couponDiscountPaise()), width));
        if (r.coinDiscountPaise() > 0) out.add(split("Coins", "-" + Money.plain(r.coinDiscountPaise()), width));
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

    static String center(String s, int w) {
        return s.length() >= w ? s : " ".repeat((w - s.length()) / 2) + s;
    }

    /** Left text and right text on one line; the left is cut if they do not fit. */
    static String split(String left, String right, int w) {
        int room = w - right.length() - 1;
        String l = left.length() > room ? left.substring(0, Math.max(room, 0)) : left;
        return l + " ".repeat(Math.max(1, w - l.length() - right.length())) + right;
    }

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
