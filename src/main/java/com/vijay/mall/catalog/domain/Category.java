package com.vijay.mall.catalog.domain;

import java.util.UUID;

/**
 * A product category (for example Dairy or Snacks) as shown in the shop menu.
 * <p>Only active categories are ever turned into this record; the {@code active} flag itself stays in the table.
 * @param id category id
 * @param nameEn English name
 * @param nameGu Gujarati name
 * @param imageUrl optional picture path, may be null
 * @param sortOrder display order, lowest first
 */
public record Category(UUID id, String nameEn, String nameGu, String imageUrl, int sortOrder) {}
