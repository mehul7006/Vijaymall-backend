package com.vijay.mall.pos.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Indian financial year (April–March) and gap-free document numbers. */
public final class InvoiceNumbers {
    public static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");

    private InvoiceNumbers() { }

    /** "2026-27" for dates from 1 Apr 2026 to 31 Mar 2027. */
    public static String financialYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return "%d-%02d".formatted(start, (start + 1) % 100);
    }

    public static String financialYear(Instant at) {
        return financialYear(at.atZone(SHOP_ZONE).toLocalDate());
    }

    /** "VSM/2026-27/000042" */
    public static String format(String prefix, String fy, long no) {
        return "%s/%s/%06d".formatted(prefix, fy, no);
    }
}
