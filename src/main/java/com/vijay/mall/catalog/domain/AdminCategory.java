package com.vijay.mall.catalog.domain;

import java.util.UUID;

/**
 * A category as the shop owner sees it: like {@link Category} but also carrying the {@code active} flag, because the admin
 * list includes hidden categories.
 * @param id category id
 * @param nameEn English name
 * @param nameGu Gujarati name
 * @param imageUrl optional picture path, may be null
 * @param sortOrder display order, lowest first
 * @param active false when the category is hidden from the shop
 */
public record AdminCategory(UUID id, String nameEn, String nameGu, String imageUrl, int sortOrder, boolean active) {}
