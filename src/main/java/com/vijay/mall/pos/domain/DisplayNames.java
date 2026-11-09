package com.vijay.mall.pos.domain;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Builds the name a product is shown under at the counter. */
public final class DisplayNames {
    private DisplayNames() { }

    /**
     * Name plus variant name plus pack size, for example {@code Basmati Rice Premium 1 kg}. A variant word already in the name is
     * not repeated, and a pack size already written in the name (as a phrase) is left out.
     *
     * @param variant the variant name; null or blank when there is none
     * @param packSize the pack size; null or blank when there is none
     */
    public static String of(String name, String variant, String packSize) {
        StringBuilder out = new StringBuilder(name == null ? "" : name.strip());
        Set<String> seen = new HashSet<>();
        for (String w : out.toString().toLowerCase(Locale.ROOT).split("\\s+")) seen.add(w);
        if (variant != null) {
            for (String w : variant.strip().split("\\s+")) {
                if (w.isEmpty() || !seen.add(w.toLowerCase(Locale.ROOT))) continue;
                out.append(out.isEmpty() ? "" : " ").append(w);
            }
        }
        String pack = packSize == null ? "" : packSize.strip().replaceAll("\\s+", " ");
        String padded = " " + out.toString().toLowerCase(Locale.ROOT) + " ";
        if (!pack.isEmpty() && !padded.contains(" " + pack.toLowerCase(Locale.ROOT) + " ")) {
            out.append(out.isEmpty() ? "" : " ").append(pack);
        }
        return out.toString();
    }
}
